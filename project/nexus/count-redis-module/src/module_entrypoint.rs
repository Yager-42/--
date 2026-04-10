use redis_module::{redis_module, Context, RedisResult, RedisString, RedisValue};

use crate::{
    count_int::COUNT_INT_REDIS_TYPE,
    roaring_state::ROARING_STATE_REDIS_TYPE,
    commands::{
        count_del_command, count_get_command, count_getall_command, count_incrby_command,
        count_mgetall_command, count_set_command, reaction_apply_command, reaction_state_command,
        reaction_restore_state_command,
    },
    module_name,
    scaffold_command_name,
    scaffold_command_response,
};

fn count_module_unimplemented(_: &Context, _: Vec<RedisString>) -> RedisResult {
    Ok(RedisValue::SimpleStringStatic(scaffold_command_response()))
}

redis_module! {
    name: module_name(),
    version: 1,
    allocator: (redis_module::alloc::RedisAlloc, redis_module::alloc::RedisAlloc),
    data_types: [COUNT_INT_REDIS_TYPE, ROARING_STATE_REDIS_TYPE],
    commands: [
        [scaffold_command_name(), count_module_unimplemented, "", 0, 0, 0],
        ["count.get", count_get_command, "readonly", 0, 0, 0],
        ["count.incrby", count_incrby_command, "write", 0, 0, 0],
        ["count.getall", count_getall_command, "readonly", 0, 0, 0],
        ["count.mgetall", count_mgetall_command, "readonly", 0, 0, 0],
        ["count.set", count_set_command, "write", 0, 0, 0],
        ["count.del", count_del_command, "write", 0, 0, 0],
        ["reaction.apply", reaction_apply_command, "write", 0, 0, 0],
        ["reaction.state", reaction_state_command, "readonly", 0, 0, 0],
        ["reaction.restore_state", reaction_restore_state_command, "write", 0, 0, 0],
    ],
}
