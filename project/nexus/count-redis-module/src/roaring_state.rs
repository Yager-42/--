use std::collections::HashMap;
use std::ffi::CString;
use std::io::Cursor;
use std::os::raw::c_void;

use redis_module::{native_types::RedisType, raw};
use roaring::RoaringTreemap;

use crate::{
    count_int::{CountInt, COUNT_MAX},
    errors::CountRedisError,
    schema_for_key,
};

pub static ROARING_STATE_REDIS_TYPE: RedisType = RedisType::new(
    "roarstat1",
    1,
    raw::RedisModuleTypeMethods {
        version: raw::REDISMODULE_TYPE_METHOD_VERSION as u64,
        rdb_load: Some(load_roaring_state),
        rdb_save: Some(save_roaring_state),
        aof_rewrite: Some(rewrite_roaring_state_aof),
        mem_usage: None,
        digest: None,
        free: Some(free_roaring_state),
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

#[derive(Debug, Clone, Default, PartialEq)]
pub struct RoaringState {
    members: RoaringTreemap,
}

impl RoaringState {
    pub fn contains(&self, user_id: u64) -> bool {
        self.members.contains(user_id)
    }

    pub fn add(&mut self, user_id: u64) {
        self.members.insert(user_id);
    }

    pub fn remove(&mut self, user_id: u64) {
        self.members.remove(user_id);
    }

    pub fn member_ids(&self) -> impl Iterator<Item = u64> + '_ {
        self.members.iter()
    }
}

#[derive(Debug, Clone, Default)]
pub struct ReactionStore {
    states: HashMap<String, RoaringState>,
    counts: HashMap<String, CountInt>,
}

impl ReactionStore {
    pub fn reaction_state(&self, state_key: &str, user_id: u64) -> Result<bool, CountRedisError> {
        self.validate_state_key(state_key)?;
        Ok(self.states.get(state_key).map(|state| state.contains(user_id)).unwrap_or(false))
    }

    pub fn state_member_ids(&self, state_key: &str) -> Result<Vec<u64>, CountRedisError> {
        self.validate_state_key(state_key)?;
        Ok(self.states.get(state_key).map(|state| state.member_ids().collect()).unwrap_or_default())
    }

    pub fn count_pairs(&self, count_key: &str) -> Result<Vec<(String, u64)>, CountRedisError> {
        let schema = schema_for_key(count_key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let mut pairs = Vec::with_capacity(schema.fields().len());
        for &(field, slot) in schema.fields() {
            let value = self.counts.get(count_key).map(|count_int| count_int.slot_value(schema, slot)).transpose()?.unwrap_or(0);
            pairs.push((field.to_string(), value));
        }
        Ok(pairs)
    }

    pub fn reaction_apply(
        &mut self,
        state_key: &str,
        count_key: &str,
        user_id: u64,
        desired_state: i64,
        field: &str,
    ) -> Result<(u64, i64), CountRedisError> {
        let desired_state = match desired_state {
            0 => false,
            1 => true,
            _ => return Err(CountRedisError::InvalidDesiredState),
        };

        self.validate_state_count_pair(state_key, count_key)?;
        let schema = schema_for_key(count_key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema.field_slot(field).ok_or(CountRedisError::UnknownField)?;

        let current_state = self.reaction_state(state_key, user_id)?;
        let Some(count_int) = self.counts.get(count_key) else {
            if current_state {
                return Err(CountRedisError::ReactionCorruptState);
            }
            if !desired_state {
                return Ok((0, 0));
            }
            let mut count_int = CountInt::zeroed(schema);
            let current_count = count_int.incrby(schema, slot, 1)?;
            let state = self.states.entry(state_key.to_string()).or_default();
            state.add(user_id);
            self.counts.insert(count_key.to_string(), count_int);
            return Ok((current_count, 1));
        };

        let current_count = count_int.slot_value(schema, slot)?;
        if current_state == desired_state {
            return Ok((current_count, 0));
        }
        if current_state && current_count == 0 {
            return Err(CountRedisError::ReactionCorruptState);
        }
        if !current_state && desired_state && current_count == COUNT_MAX {
            return Err(CountRedisError::CountOverflow);
        }

        let delta = if desired_state { 1 } else { -1 };
        let count_int = self.counts.get_mut(count_key).expect("count should exist after guard");
        let next_count = count_int.incrby(schema, slot, delta)?;
        let state = self.states.entry(state_key.to_string()).or_default();
        if desired_state {
            state.add(user_id);
        } else {
            state.remove(user_id);
        }
        Ok((next_count, delta))
    }

    pub fn seed_reaction_state(&mut self, state_key: &str, user_id: u64) -> Result<(), CountRedisError> {
        self.validate_state_key(state_key)?;
        self.states.entry(state_key.to_string()).or_default().add(user_id);
        Ok(())
    }

    pub fn seed_count_slot(&mut self, count_key: &str, field: &str, value: u64) -> Result<(), CountRedisError> {
        let schema = schema_for_key(count_key).ok_or(CountRedisError::UnknownKeyFamily)?;
        let slot = schema.field_slot(field).ok_or(CountRedisError::UnknownField)?;
        let count_int = self.counts.entry(count_key.to_string()).or_insert_with(|| CountInt::zeroed(schema));
        count_int.set_slot_value(schema, slot, value)
    }

    fn validate_state_key(&self, state_key: &str) -> Result<(), CountRedisError> {
        state_family(state_key).ok_or(CountRedisError::UnknownStateFamily).map(|_| ())
    }

    fn validate_state_count_pair(&self, state_key: &str, count_key: &str) -> Result<(), CountRedisError> {
        let state_family = state_family(state_key).ok_or(CountRedisError::UnknownStateFamily)?;
        let count_family = count_family(count_key).ok_or(CountRedisError::UnknownKeyFamily)?;
        if state_family == count_family {
            Ok(())
        } else {
            Err(CountRedisError::StateCountMismatch)
        }
    }
}

fn state_family(state_key: &str) -> Option<&'static str> {
    if matches_state_key(state_key, "post_like") {
        return Some("post");
    }
    if matches_state_key(state_key, "comment_like") {
        return Some("comment");
    }
    None
}

fn count_family(count_key: &str) -> Option<&'static str> {
    if count_key.starts_with("count:post:{") {
        return Some("post");
    }
    if count_key.starts_with("count:comment:{") {
        return Some("comment");
    }
    None
}

fn matches_state_key(key: &str, family: &str) -> bool {
    let prefix = format!("state:{family}:{{");
    let Some(id_part) = key.strip_prefix(&prefix) else {
        return false;
    };
    let Some(inner) = id_part.strip_suffix('}') else {
        return false;
    };
    !inner.is_empty() && !inner.contains('{') && !inner.contains('}')
}

unsafe extern "C" fn free_roaring_state(value: *mut c_void) {
    drop(Box::from_raw(value.cast::<RoaringState>()));
}

unsafe extern "C" fn save_roaring_state(rdb: *mut raw::RedisModuleIO, value: *mut c_void) {
    let state = &*value.cast::<RoaringState>();
    let mut bytes = Vec::new();
    if state.members.serialize_into(&mut bytes).is_err() {
        return;
    }
    raw::save_unsigned(rdb, bytes.len() as u64);
    raw::save_slice(rdb, &bytes);
}

unsafe extern "C" fn load_roaring_state(rdb: *mut raw::RedisModuleIO, encver: i32) -> *mut c_void {
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
    let members = match RoaringTreemap::deserialize_from(&mut Cursor::new(bytes)) {
        Ok(members) => members,
        Err(_) => return std::ptr::null_mut(),
    };
    Box::into_raw(Box::new(RoaringState { members })).cast::<c_void>()
}


unsafe extern "C" fn rewrite_roaring_state_aof(
    aof: *mut raw::RedisModuleIO,
    key: *mut raw::RedisModuleString,
    value: *mut c_void,
) {
    let state = &*value.cast::<RoaringState>();
    let command = CString::new("REACTION.RESTORE_STATE").expect("static command name should be valid");
    let fmt = CString::new("sl").expect("static aof fmt should be valid");

    for user_id in state.member_ids() {
        raw::RedisModule_EmitAOF.unwrap()(
            aof,
            command.as_ptr(),
            fmt.as_ptr(),
            key,
            user_id as i64,
        );
    }
}
