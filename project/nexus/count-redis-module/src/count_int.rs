use std::collections::HashMap;
use std::ffi::CString;
use std::os::raw::c_void;

use redis_module::{native_types::RedisType, raw};
use crate::{
    errors::CountRedisError,
    schema_for_key,
    CountSchema,
};

pub const SLOT_WIDTH_BYTES: usize = 5;
pub const COUNT_MAX: u64 = (1u64 << 40) - 1;
pub static COUNT_INT_REDIS_TYPE: RedisType = RedisType::new(
    "countint1",
    1,
    raw::RedisModuleTypeMethods {
        version: raw::REDISMODULE_TYPE_METHOD_VERSION as u64,
        rdb_load: Some(load_count_int),
        rdb_save: Some(save_count_int),
        aof_rewrite: Some(rewrite_count_int_aof),
        mem_usage: None,
        digest: None,
        free: Some(free_count_int),
        aux_load: None,
        aux_save: None,
        aux_save2: None,
        aux_save_triggers: 0,
        free_effort: None,
        unlink: None,
        copy: None,
        defrag: None,
        copy2: None,
        free_effort2: None,
        mem_usage2: None,
        unlink2: None,
    },
);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CountInt {
    bytes: Vec<u8>,
}

impl CountInt {
    pub fn zeroed(schema: &CountSchema) -> Self {
        Self {
            bytes: vec![0; schema.total_payload_bytes],
        }
    }

    pub fn slot_value(&self, schema: &CountSchema, slot: usize) -> Result<u64, CountRedisError> {
        let offset = slot_offset(schema, slot)?;
        decode_int5(&self.bytes[offset..offset + SLOT_WIDTH_BYTES])
    }

    pub fn set_slot_value(
        &mut self,
        schema: &CountSchema,
        slot: usize,
        value: u64,
    ) -> Result<(), CountRedisError> {
        let offset = slot_offset(schema, slot)?;
        let encoded = encode_int5(value)?;
        self.bytes[offset..offset + SLOT_WIDTH_BYTES].copy_from_slice(&encoded);
        Ok(())
    }

    pub fn incrby(
        &mut self,
        schema: &CountSchema,
        slot: usize,
        delta: i64,
    ) -> Result<u64, CountRedisError> {
        let current = self.slot_value(schema, slot)?;
        let next = apply_delta(current, delta)?;
        self.set_slot_value(schema, slot, next)?;
        Ok(next)
    }
}

#[derive(Debug, Clone, Default)]
pub struct CounterStore {
    objects: HashMap<String, CountInt>,
}

impl CounterStore {
    pub fn count_get(&self, key: &str, field: &str) -> Result<u64, CountRedisError> {
        let schema = schema_for_key(key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema
            .field_slot(field)
            .ok_or(CountRedisError::UnknownField)?;
        let Some(count_int) = self.objects.get(key) else {
            return Ok(0);
        };
        count_int.slot_value(schema, slot)
    }

    pub fn count_incrby(
        &mut self,
        key: &str,
        field: &str,
        delta: i64,
    ) -> Result<u64, CountRedisError> {
        let schema = schema_for_key(key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema
            .field_slot(field)
            .ok_or(CountRedisError::UnknownField)?;
        let count_int = self
            .objects
            .entry(key.to_string())
            .or_insert_with(|| CountInt::zeroed(schema));
        count_int.incrby(schema, slot, delta)
    }

    pub fn count_set(
        &mut self,
        key: &str,
        field: &str,
        value: u64,
    ) -> Result<(), CountRedisError> {
        let schema = schema_for_key(key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema
            .field_slot(field)
            .ok_or(CountRedisError::UnknownField)?;
        let count_int = self
            .objects
            .entry(key.to_string())
            .or_insert_with(|| CountInt::zeroed(schema));
        count_int.set_slot_value(schema, slot, value)
    }

    pub fn count_set_i64(
        &mut self,
        key: &str,
        field: &str,
        value: i64,
    ) -> Result<(), CountRedisError> {
        if value < 0 {
            return Err(CountRedisError::CountNegativeValue);
        }

        let schema = schema_for_key(key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema
            .field_slot(field)
            .ok_or(CountRedisError::UnknownField)?;
        let count_int = self
            .objects
            .entry(key.to_string())
            .or_insert_with(|| CountInt::zeroed(schema));
        count_int.set_slot_value(schema, slot, value as u64)
    }

    pub fn count_del(&mut self, key: &str) -> bool {
        self.objects.remove(key).is_some()
    }

    pub fn count_getall(
        &self,
        key: &str,
    ) -> Result<Vec<(String, u64)>, CountRedisError> {
        let schema = schema_for_key(key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let mut result = Vec::new();
        for &(field, slot) in schema.fields() {
            let value = self
                .objects
                .get(key)
                .map(|count_int| count_int.slot_value(schema, slot))
                .transpose()?
                .unwrap_or(0);
            result.push((field.to_string(), value));
        }
        Ok(result)
    }

    pub fn count_mgetall(
        &self,
        keys: &[&str],
    ) -> Result<Vec<(String, Vec<(String, u64)>)>, CountRedisError> {
        let mut result = Vec::with_capacity(keys.len());
        for key in keys {
            result.push(((*key).to_string(), self.count_getall(key)?));
        }
        Ok(result)
    }
}

pub fn encode_int5(value: u64) -> Result<[u8; SLOT_WIDTH_BYTES], CountRedisError> {
    if value > COUNT_MAX {
        return Err(CountRedisError::CountOverflow);
    }

    Ok([
        ((value >> 32) & 0xff) as u8,
        ((value >> 24) & 0xff) as u8,
        ((value >> 16) & 0xff) as u8,
        ((value >> 8) & 0xff) as u8,
        (value & 0xff) as u8,
    ])
}

pub fn decode_int5(bytes: &[u8]) -> Result<u64, CountRedisError> {
    if bytes.len() != SLOT_WIDTH_BYTES {
        return Err(CountRedisError::CountNegativeValue);
    }

    Ok(((bytes[0] as u64) << 32)
        | ((bytes[1] as u64) << 24)
        | ((bytes[2] as u64) << 16)
        | ((bytes[3] as u64) << 8)
        | (bytes[4] as u64))
}

fn slot_offset(schema: &CountSchema, slot: usize) -> Result<usize, CountRedisError> {
    let offset = slot
        .checked_mul(schema.slot_width_bytes)
        .ok_or(CountRedisError::CountOverflow)?;
    let end = offset
        .checked_add(schema.slot_width_bytes)
        .ok_or(CountRedisError::CountOverflow)?;
    if end > schema.total_payload_bytes {
        return Err(CountRedisError::UnknownField);
    }
    Ok(offset)
}

fn apply_delta(current: u64, delta: i64) -> Result<u64, CountRedisError> {
    if delta >= 0 {
        let delta_u64 = delta as u64;
        let next = current
            .checked_add(delta_u64)
            .ok_or(CountRedisError::CountOverflow)?;
        if next > COUNT_MAX {
            return Err(CountRedisError::CountOverflow);
        }
        return Ok(next);
    }

    let abs = delta.unsigned_abs();
    if abs >= current {
        return Ok(0);
    }
    Ok(current - abs)
}

unsafe extern "C" fn free_count_int(value: *mut c_void) {
    drop(Box::from_raw(value.cast::<CountInt>()));
}

unsafe extern "C" fn save_count_int(rdb: *mut raw::RedisModuleIO, value: *mut c_void) {
    let count_int = &*value.cast::<CountInt>();
    raw::save_unsigned(rdb, count_int.bytes.len() as u64);
    raw::save_slice(rdb, &count_int.bytes);
}

unsafe extern "C" fn load_count_int(
    rdb: *mut raw::RedisModuleIO,
    encver: i32,
) -> *mut c_void {
    if encver != 1 {
        return std::ptr::null_mut();
    }

    let len = match raw::load_unsigned(rdb) {
        Ok(len) => len as usize,
        Err(_) => return std::ptr::null_mut(),
    };
    let bytes = match raw::load_string_buffer(rdb) {
        Ok(buffer) if buffer.as_ref().len() == len => buffer.as_ref().to_vec(),
        _ => return std::ptr::null_mut(),
    };

    Box::into_raw(Box::new(CountInt { bytes })).cast::<c_void>()
}

unsafe extern "C" fn rewrite_count_int_aof(
    aof: *mut raw::RedisModuleIO,
    key: *mut raw::RedisModuleString,
    value: *mut c_void,
) {
    let count_int = &*value.cast::<CountInt>();
    let mut key_len = 0;
    let key_ptr = raw::string_ptr_len(key, &mut key_len);
    let key_bytes = std::slice::from_raw_parts(key_ptr.cast::<u8>(), key_len);
    let key_str = match std::str::from_utf8(key_bytes) {
        Ok(value) => value,
        Err(_) => return,
    };
    let schema = match schema_for_key(key_str) {
        Some(schema) => schema,
        None => return,
    };
    let command = CString::new("COUNT.SET").expect("static command name should be valid");
    let fmt = CString::new("scl").expect("static aof fmt should be valid");

    for &(field, slot) in schema.fields() {
        let value = match count_int.slot_value(schema, slot) {
            Ok(value) => value,
            Err(_) => return,
        };
        let field_cstr = match CString::new(field) {
            Ok(value) => value,
            Err(_) => return,
        };
        raw::RedisModule_EmitAOF.unwrap()(
            aof,
            command.as_ptr(),
            fmt.as_ptr(),
            key,
            field_cstr.as_ptr(),
            value as i64,
        );
    }
}
