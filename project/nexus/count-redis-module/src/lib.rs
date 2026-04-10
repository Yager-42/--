#[cfg_attr(not(feature = "module-entrypoint"), allow(dead_code))]
mod commands;
mod count_int;
mod errors;
#[cfg(feature = "module-entrypoint")]
mod module_entrypoint;
mod schema;
mod roaring_state;

pub use count_int::{CountInt, CounterStore, COUNT_MAX};
pub use commands::render_mgetall_records;
pub use roaring_state::{ReactionStore, RoaringState};
pub fn module_name() -> &'static str {
    "countredis"
}

pub fn scaffold_command_name() -> &'static str {
    "count.module_unimplemented"
}

pub fn scaffold_command_response() -> &'static str {
    errors::CountRedisError::NotImplemented.message()
}

pub use errors::CountRedisError;
pub use schema::{schema_for_key, CountSchema};
