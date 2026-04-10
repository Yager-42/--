use redis_module::{error::Error, Context, RedisResult, RedisString, RedisValue};

use crate::{
    count_int::{CountInt, COUNT_INT_REDIS_TYPE, COUNT_MAX},
    errors::CountRedisError,
    roaring_state::{RoaringState, ROARING_STATE_REDIS_TYPE},
    schema_for_key,
};

pub fn count_get_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 3 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let key_name = &args[1];
    let field = args[2].try_as_str()?;
    let value = {
        let key = ctx.open_key(key_name);
        read_slot_value(&key, key_name.try_as_str()?, field)?
    };
    Ok(RedisValue::Integer(value as i64))
}

pub fn count_incrby_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 4 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let key_name = &args[1];
    let key_str = key_name.try_as_str()?;
    let field = args[2].try_as_str()?;
    let delta = parse_i64(&args[3])?;
    let schema = schema_for_key(key_str).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    let slot = schema
        .field_slot(field)
        .ok_or_else(|| redis_error(CountRedisError::UnknownField))?;

    let key = ctx.open_key_writable(key_name);
    let value = if let Some(count_int) = key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)? {
        count_int.incrby(schema, slot, delta).map_err(as_redis_error)?
    } else {
        let mut count_int = CountInt::zeroed(schema);
        let value = count_int.incrby(schema, slot, delta).map_err(as_redis_error)?;
        key.set_value(&COUNT_INT_REDIS_TYPE, count_int)?;
        value
    };

    Ok(RedisValue::Integer(value as i64))
}

pub fn count_getall_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 2 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let key_name = &args[1];
    let pairs = {
        let key = ctx.open_key(key_name);
        collect_pairs(&key, key_name.try_as_str()?)?
    };
    Ok(RedisValue::Array(flatten_pairs(&pairs)))
}

pub fn count_mgetall_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() < 2 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let mut records = Vec::with_capacity(args.len() - 1);
    for key_name in &args[1..] {
        let key_str = key_name.try_as_str()?.to_string();
        let pairs = {
            let key = ctx.open_key(key_name);
            collect_pairs(&key, key_str.as_str())?
        };
        records.push((key_str, pairs));
    }

    Ok(render_mgetall_records(records))
}

pub fn count_set_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 4 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let key_name = &args[1];
    let key_str = key_name.try_as_str()?;
    let field = args[2].try_as_str()?;
    let value = parse_count_set_value(&args[3])?;
    let schema = schema_for_key(key_str).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    let slot = schema
        .field_slot(field)
        .ok_or_else(|| redis_error(CountRedisError::UnknownField))?;

    let key = ctx.open_key_writable(key_name);
    if let Some(count_int) = key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)? {
        count_int
            .set_slot_value(schema, slot, value)
            .map_err(as_redis_error)?;
    } else {
        let mut count_int = CountInt::zeroed(schema);
        count_int
            .set_slot_value(schema, slot, value)
            .map_err(as_redis_error)?;
        key.set_value(&COUNT_INT_REDIS_TYPE, count_int)?;
    }

    Ok(RedisValue::SimpleStringStatic("OK"))
}

pub fn count_del_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 2 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let key_name = &args[1];
    let key = ctx.open_key_writable(key_name);
    let deleted = key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)?.is_some();
    if deleted {
        key.delete()?;
    }
    Ok(RedisValue::Integer(i64::from(deleted)))
}

pub fn reaction_state_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 3 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let state_key_name = &args[1];
    let user_id = parse_u64(&args[2])?;
    let state_key = ctx.open_key(state_key_name);
    let present = match state_key.get_value::<RoaringState>(&ROARING_STATE_REDIS_TYPE)? {
        Some(state) => state.contains(user_id),
        None => false,
    };
    Ok(RedisValue::Bool(present))
}

pub fn reaction_restore_state_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 3 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let state_key_name = &args[1];
    let state_key_str = state_key_name.try_as_str()?;
    validate_exact_state_key(state_key_str)?;
    let user_id = parse_u64(&args[2])?;

    let state_key = ctx.open_key_writable(state_key_name);
    if let Some(state) = state_key.get_value::<RoaringState>(&ROARING_STATE_REDIS_TYPE)? {
        state.add(user_id);
    } else {
        let mut state = RoaringState::default();
        state.add(user_id);
        state_key.set_value(&ROARING_STATE_REDIS_TYPE, state)?;
    }

    Ok(RedisValue::SimpleStringStatic("OK"))
}

pub fn reaction_apply_command(ctx: &Context, args: Vec<RedisString>) -> RedisResult {
    if args.len() != 6 {
        return Err(redis_error(CountRedisError::WrongArity).into());
    }

    let state_key_name = &args[1];
    let count_key_name = &args[2];
    let user_id = parse_u64(&args[3])?;
    let desired_state = parse_desired_state(&args[4])?;
    let field = args[5].try_as_str()?;
    let count_key_str = count_key_name.try_as_str()?;
    let state_key_str = state_key_name.try_as_str()?;
    validate_reaction_key_pair(state_key_str, count_key_str)?;

    {
        let state_key = ctx.open_key(state_key_name);
        let _ = state_key.get_value::<RoaringState>(&ROARING_STATE_REDIS_TYPE)?;
    }
    {
        let count_key = ctx.open_key(count_key_name);
        let _ = count_key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)?;
    }

    let schema = schema_for_key(count_key_str).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    let slot = schema.field_slot(field).ok_or_else(|| redis_error(CountRedisError::UnknownField))?;

    let state_present = {
        let state_key = ctx.open_key(state_key_name);
        match state_key.get_value::<RoaringState>(&ROARING_STATE_REDIS_TYPE)? {
            Some(state) => state.contains(user_id),
            None => false,
        }
    };

    let current_count = {
        let count_key = ctx.open_key(count_key_name);
        match count_key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)? {
            Some(count_int) => count_int.slot_value(schema, slot).map_err(as_redis_error)?,
            None => 0,
        }
    };

    if state_present == desired_state {
        return Ok(RedisValue::Array(vec![
            RedisValue::Integer(current_count as i64),
            RedisValue::Integer(0),
        ]));
    }
    if state_present && current_count == 0 {
        return Err(redis_error(CountRedisError::ReactionCorruptState).into());
    }
    if !state_present && desired_state && current_count == COUNT_MAX {
        return Err(redis_error(CountRedisError::CountOverflow).into());
    }

    let delta = if desired_state { 1 } else { -1 };
    let next_count = if desired_state {
        current_count.checked_add(1).ok_or_else(|| redis_error(CountRedisError::CountOverflow))?
    } else {
        current_count - 1
    };
    if next_count > COUNT_MAX {
        return Err(redis_error(CountRedisError::CountOverflow).into());
    }

    let count_key = ctx.open_key_writable(count_key_name);
    if let Some(count_int) = count_key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)? {
        count_int.set_slot_value(schema, slot, next_count).map_err(as_redis_error)?;
    } else {
        if state_present {
            return Err(redis_error(CountRedisError::ReactionCorruptState).into());
        }
        let mut count_int = CountInt::zeroed(schema);
        count_int.set_slot_value(schema, slot, next_count).map_err(as_redis_error)?;
        count_key.set_value(&COUNT_INT_REDIS_TYPE, count_int)?;
    }

    let state_key = ctx.open_key_writable(state_key_name);
    if let Some(state) = state_key.get_value::<RoaringState>(&ROARING_STATE_REDIS_TYPE)? {
        if desired_state {
            state.add(user_id);
        } else {
            state.remove(user_id);
        }
    } else if desired_state {
        let mut state = RoaringState::default();
        state.add(user_id);
        state_key.set_value(&ROARING_STATE_REDIS_TYPE, state)?;
    } else {
        return Err(redis_error(CountRedisError::ReactionCorruptState).into());
    }

    Ok(RedisValue::Array(vec![
        RedisValue::Integer(next_count as i64),
        RedisValue::Integer(delta),
    ]))
}

pub fn render_mgetall_records(records: Vec<(String, Vec<(String, u64)>)>) -> RedisValue {
    RedisValue::Array(
        records
            .into_iter()
            .map(|(key, pairs)| {
                let mut record = vec![RedisValue::BulkString(key)];
                record.extend(flatten_pairs(&pairs));
                RedisValue::Array(record)
            })
            .collect(),
    )
}

fn collect_pairs(
    key: &redis_module::key::RedisKey,
    key_str: &str,
) -> Result<Vec<(String, u64)>, Error> {
    let schema = schema_for_key(key_str).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    let count_int = key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)?;
    let mut pairs = Vec::with_capacity(schema.fields().len());
    for &(field, slot) in schema.fields() {
        let value = match count_int {
            Some(count_int) => count_int.slot_value(schema, slot).map_err(as_redis_error)?,
            None => 0,
        };
        pairs.push((field.to_string(), value));
    }
    Ok(pairs)
}

fn read_slot_value(
    key: &redis_module::key::RedisKey,
    key_str: &str,
    field: &str,
) -> Result<u64, Error> {
    let schema = schema_for_key(key_str).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    let slot = schema
        .field_slot(field)
        .ok_or_else(|| redis_error(CountRedisError::UnknownField))?;

    match key.get_value::<CountInt>(&COUNT_INT_REDIS_TYPE)? {
        Some(count_int) => count_int.slot_value(schema, slot).map_err(as_redis_error),
        None => Ok(0),
    }
}

fn flatten_pairs(pairs: &[(String, u64)]) -> Vec<RedisValue> {
    let mut values = Vec::with_capacity(pairs.len() * 2);
    for (field, value) in pairs {
        values.push(RedisValue::BulkString(field.clone()));
        values.push(RedisValue::Integer(*value as i64));
    }
    values
}

fn parse_i64(arg: &RedisString) -> Result<i64, Error> {
    arg.try_as_str()?
        .parse::<i64>()
        .map_err(|_| redis_error(CountRedisError::InvalidInteger))
}

fn parse_u64(arg: &RedisString) -> Result<u64, Error> {
    arg.try_as_str()?
        .parse::<u64>()
        .map_err(|_| redis_error(CountRedisError::InvalidInteger))
}

fn parse_desired_state(arg: &RedisString) -> Result<bool, Error> {
    match parse_i64(arg)? {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(redis_error(CountRedisError::InvalidDesiredState)),
    }
}

fn parse_count_set_value(arg: &RedisString) -> Result<u64, Error> {
    let raw = arg.try_as_str()?;

    if raw.starts_with('-') {
        return Err(redis_error(CountRedisError::CountNegativeValue));
    }

    match raw.parse::<u64>() {
        Ok(value) => Ok(value),
        Err(_) => {
            if raw.parse::<i64>().is_ok() {
                Err(redis_error(CountRedisError::CountNegativeValue))
            } else {
                Err(redis_error(CountRedisError::InvalidInteger))
            }
        }
    }
}

fn validate_reaction_key_pair(state_key: &str, count_key: &str) -> Result<(), Error> {
    let state_family = exact_state_family(state_key).ok_or_else(|| redis_error(CountRedisError::UnknownStateFamily))?;
    let count_family = exact_count_family(count_key).ok_or_else(|| redis_error(CountRedisError::UnknownKeyFamily))?;
    if state_family == count_family {
        Ok(())
    } else {
        Err(redis_error(CountRedisError::StateCountMismatch))
    }
}

fn validate_exact_state_key(state_key: &str) -> Result<(), Error> {
    exact_state_family(state_key)
        .ok_or_else(|| redis_error(CountRedisError::UnknownStateFamily))
        .map(|_| ())
}

fn exact_state_family(state_key: &str) -> Option<&'static str> {
    if matches_exact_key_shape(state_key, "state:post_like:{") {
        return Some("post");
    }
    if matches_exact_key_shape(state_key, "state:comment_like:{") {
        return Some("comment");
    }
    None
}

fn exact_count_family(count_key: &str) -> Option<&'static str> {
    if matches_exact_key_shape(count_key, "count:post:{") {
        return Some("post");
    }
    if matches_exact_key_shape(count_key, "count:comment:{") {
        return Some("comment");
    }
    None
}

fn matches_exact_key_shape(key: &str, prefix: &str) -> bool {
    let Some(id_part) = key.strip_prefix(prefix) else {
        return false;
    };
    let Some(inner) = id_part.strip_suffix('}') else {
        return false;
    };
    !inner.is_empty() && !inner.contains('{') && !inner.contains('}')
}

fn as_redis_error(error: CountRedisError) -> Error {
    redis_error(error)
}

fn redis_error(error: CountRedisError) -> Error {
    Error::generic(error.message())
}
