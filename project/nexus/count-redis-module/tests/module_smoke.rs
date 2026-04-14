#[test]
fn scaffold_exposes_module_metadata() {
    assert_eq!(count_redis_module::module_name(), "countredis");
    assert_eq!(
        count_redis_module::scaffold_command_name(),
        "count.module_unimplemented"
    );
    assert_eq!(
        count_redis_module::scaffold_command_response(),
        "not implemented"
    );
}

#[test]
fn post_counter_schema_exposes_like_slot_zero() {
    let schema = count_redis_module::schema_for_key("count:post:{42}")
        .expect("post schema should resolve");

    assert_eq!(schema.name, "post_counter");
    assert_eq!(schema.slot_width_bytes, 5);
    assert_eq!(schema.total_payload_bytes, 5);
    assert_eq!(schema.field_slot("like"), Some(0));
}

#[test]
fn comment_counter_schema_exposes_like_and_reply_slots() {
    let schema = count_redis_module::schema_for_key("count:comment:{99}")
        .expect("comment schema should resolve");

    assert_eq!(schema.name, "comment_counter");
    assert_eq!(schema.slot_width_bytes, 5);
    assert_eq!(schema.total_payload_bytes, 10);
    assert_eq!(schema.field_slot("like"), Some(0));
    assert_eq!(schema.field_slot("reply"), Some(1));
}

#[test]
fn invalid_field_for_family_returns_error() {
    let schema = count_redis_module::schema_for_key("count:user:{7}")
        .expect("user schema should resolve");

    assert_eq!(
        schema.validate_field("like").unwrap_err(),
        "ERR UNKNOWN_FIELD"
    );
}

#[test]
fn malformed_family_key_does_not_resolve_schema() {
    assert!(count_redis_module::schema_for_key("count:post:{").is_none());
    assert!(count_redis_module::schema_for_key("count:post:{42}:extra").is_none());
    assert!(count_redis_module::schema_for_key("count:post:{42}garbage}").is_none());
    assert!(count_redis_module::schema_for_key("count:post:{{42}}").is_none());
    assert!(count_redis_module::schema_for_key("count:comment:42").is_none());
}

#[test]
fn count_get_missing_returns_zero() {
    let store = count_redis_module::CounterStore::default();

    assert_eq!(
        store
            .count_get("count:post:{404}", "like")
            .expect("missing key should read as zero"),
        0
    );
}

#[test]
fn count_incrby_creates_zero_initialized_object() {
    let mut store = count_redis_module::CounterStore::default();

    let updated = store
        .count_incrby("count:post:{42}", "like", 3)
        .expect("increment should create object");

    assert_eq!(updated, 3);
    assert_eq!(
        store
            .count_get("count:post:{42}", "like")
            .expect("count get should succeed"),
        3
    );
}

#[test]
fn count_incrby_clamps_underflow_to_zero() {
    let mut store = count_redis_module::CounterStore::default();

    store
        .count_set("count:comment:{5}", "reply", 2)
        .expect("count set should seed state");

    let updated = store
        .count_incrby("count:comment:{5}", "reply", -9)
        .expect("underflow should clamp");

    assert_eq!(updated, 0);
    assert_eq!(
        store
            .count_get("count:comment:{5}", "reply")
            .expect("count get should succeed"),
        0
    );
}

#[test]
fn count_incrby_rejects_overflow() {
    let mut store = count_redis_module::CounterStore::default();

    store
        .count_set("count:user:{8}", "follower", count_redis_module::COUNT_MAX)
        .expect("count set should seed max value");

    let err = store
        .count_incrby("count:user:{8}", "follower", 1)
        .expect_err("overflow should fail");

    assert_eq!(err.message(), "ERR COUNT_OVERFLOW");
    assert_eq!(
        store
            .count_get("count:user:{8}", "follower")
            .expect("count get should still succeed"),
        count_redis_module::COUNT_MAX
    );
}

#[test]
fn count_set_rejects_negative_value() {
    let mut store = count_redis_module::CounterStore::default();

    let err = store
        .count_set_i64("count:user:{9}", "following", -1)
        .expect_err("negative set should fail");

    assert_eq!(err.message(), "ERR COUNT_NEGATIVE_VALUE");
    assert_eq!(
        store
            .count_get("count:user:{9}", "following")
            .expect("count get should still succeed"),
        0
    );
}

#[test]
fn count_getall_returns_schema_ordered_pairs() {
    let mut store = count_redis_module::CounterStore::default();

    store
        .count_set("count:comment:{99}", "reply", 7)
        .expect("count set should seed reply");
    store
        .count_set("count:comment:{99}", "like", 11)
        .expect("count set should seed like");

    let pairs = store
        .count_getall("count:comment:{99}")
        .expect("count getall should succeed");

    assert_eq!(
        pairs,
        vec![
            ("like".to_string(), 11),
            ("reply".to_string(), 7),
        ]
    );
}

#[test]
fn count_mgetall_returns_request_order_records() {
    let mut store = count_redis_module::CounterStore::default();

    store
        .count_set("count:comment:{1}", "like", 5)
        .expect("count set should seed like");
    store
        .count_set("count:user:{2}", "following", 8)
        .expect("count set should seed following");

    let records = store
        .count_mgetall(&["count:user:{2}", "count:comment:{1}", "count:post:{404}"])
        .expect("count mgetall should succeed");

    assert_eq!(
        records,
        vec![
            (
                "count:user:{2}".to_string(),
                vec![
                    ("following".to_string(), 8),
                    ("follower".to_string(), 0),
                ],
            ),
            (
                "count:comment:{1}".to_string(),
                vec![
                    ("like".to_string(), 5),
                    ("reply".to_string(), 0),
                ],
            ),
            (
                "count:post:{404}".to_string(),
                vec![("like".to_string(), 0)],
            ),
        ]
    );
}

#[test]
fn count_set_rejects_overflow_value() {
    let mut store = count_redis_module::CounterStore::default();

    let err = store
        .count_set("count:user:{9}", "following", count_redis_module::COUNT_MAX + 1)
        .expect_err("overflow set should fail");

    assert_eq!(err.message(), "ERR COUNT_OVERFLOW");
}

#[test]
fn count_mgetall_render_returns_request_order_array_shape() {
    let rendered = count_redis_module::render_mgetall_records(vec![
        (
            "count:user:{2}".to_string(),
            vec![
                ("following".to_string(), 8),
                ("follower".to_string(), 0),
            ],
        ),
        (
            "count:post:{404}".to_string(),
            vec![("like".to_string(), 0)],
        ),
    ]);

    assert_eq!(
        rendered,
        redis_module::RedisValue::Array(vec![
            redis_module::RedisValue::Array(vec![
                redis_module::RedisValue::BulkString("count:user:{2}".to_string()),
                redis_module::RedisValue::BulkString("following".to_string()),
                redis_module::RedisValue::Integer(8),
                redis_module::RedisValue::BulkString("follower".to_string()),
                redis_module::RedisValue::Integer(0),
            ]),
            redis_module::RedisValue::Array(vec![
                redis_module::RedisValue::BulkString("count:post:{404}".to_string()),
                redis_module::RedisValue::BulkString("like".to_string()),
                redis_module::RedisValue::Integer(0),
            ]),
        ])
    );
}

#[test]
fn reaction_apply_adds_missing_member_and_increments_count() {
    let mut store = count_redis_module::ReactionStore::default();

    let reply = store
        .reaction_apply("state:post_like:{42}", "count:post:{42}", 7, 1, "like")
        .expect("reaction apply should succeed");

    assert_eq!(reply, (1, 1));
    assert!(store
        .reaction_state("state:post_like:{42}", 7)
        .expect("reaction state should read"));
}

#[test]
fn reaction_apply_is_noop_when_state_is_unchanged() {
    let mut store = count_redis_module::ReactionStore::default();
    store
        .reaction_apply("state:comment_like:{8}", "count:comment:{8}", 99, 1, "like")
        .expect("initial apply should succeed");

    let reply = store
        .reaction_apply("state:comment_like:{8}", "count:comment:{8}", 99, 1, "like")
        .expect("noop apply should succeed");

    assert_eq!(reply, (1, 0));
}

#[test]
fn reaction_apply_rejects_corrupt_positive_transition_when_state_exists_without_count() {
    let mut store = count_redis_module::ReactionStore::default();
    store
        .seed_reaction_state("state:post_like:{10}", 202)
        .expect("seed state should succeed");

    let err = store
        .reaction_apply("state:post_like:{10}", "count:post:{10}", 202, 1, "like")
        .expect_err("corrupt positive transition should fail");

    assert_eq!(err.message(), "ERR REACTION_CORRUPT_STATE");
}

#[test]
fn reaction_restore_state_rehydrates_members_without_touching_count() {
    let mut store = count_redis_module::ReactionStore::default();
    store
        .seed_reaction_state("state:comment_like:{88}", 1001)
        .expect("seed first member should succeed");
    store
        .seed_reaction_state("state:comment_like:{88}", 1002)
        .expect("seed second member should succeed");
    store
        .seed_count_slot("count:comment:{88}", "like", 2)
        .expect("seed count should succeed");

    assert!(store
        .reaction_state("state:comment_like:{88}", 1001)
        .expect("state read should succeed"));
    assert_eq!(
        store.count_pairs("count:comment:{88}").expect("count read should succeed"),
        vec![("like".to_string(), 2), ("reply".to_string(), 0)]
    );
}

#[test]
fn reaction_state_key_validation_rejects_malformed_pairs() {
    let mut store = count_redis_module::ReactionStore::default();

    let err = store
        .reaction_apply("state:post_like:{42}oops}", "count:post:{42}", 1, 1, "like")
        .expect_err("malformed state key should fail");
    assert_eq!(err.message(), "ERR UNKNOWN_STATE_FAMILY");
}

#[test]
fn reaction_apply_rejects_corrupt_negative_transition() {
    let mut store = count_redis_module::ReactionStore::default();
    store
        .seed_reaction_state("state:post_like:{9}", 101)
        .expect("seed state should succeed");

    let err = store
        .reaction_apply("state:post_like:{9}", "count:post:{9}", 101, 0, "like")
        .expect_err("corrupt state should fail");

    assert_eq!(err.message(), "ERR REACTION_CORRUPT_STATE");
}

#[test]
fn reaction_state_returns_false_for_missing_key() {
    let store = count_redis_module::ReactionStore::default();

    assert!(!store
        .reaction_state("state:comment_like:{404}", 77)
        .expect("missing key should read false"));
}

#[test]
fn reaction_apply_returns_current_count_and_delta() {
    let mut store = count_redis_module::ReactionStore::default();

    let added = store
        .reaction_apply("state:post_like:{66}", "count:post:{66}", 11, 1, "like")
        .expect("add should succeed");
    let removed = store
        .reaction_apply("state:post_like:{66}", "count:post:{66}", 11, 0, "like")
        .expect("remove should succeed");

    assert_eq!(added, (1, 1));
    assert_eq!(removed, (0, -1));
}
