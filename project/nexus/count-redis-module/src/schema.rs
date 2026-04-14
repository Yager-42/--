#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CountSchema {
    pub name: &'static str,
    pub slot_width_bytes: usize,
    pub total_payload_bytes: usize,
    fields: &'static [(&'static str, usize)],
}

impl CountSchema {
    pub fn fields(&self) -> &'static [(&'static str, usize)] {
        self.fields
    }

    pub fn field_slot(&self, field: &str) -> Option<usize> {
        self.fields
            .iter()
            .find_map(|(name, slot)| (*name == field).then_some(*slot))
    }

    pub fn validate_field(&self, field: &str) -> Result<usize, &'static str> {
        self.field_slot(field).ok_or("ERR UNKNOWN_FIELD")
    }
}

const SLOT_WIDTH_BYTES: usize = 5;

const POST_COUNTER_FIELDS: [(&str, usize); 1] = [("like", 0)];
const COMMENT_COUNTER_FIELDS: [(&str, usize); 2] = [("like", 0), ("reply", 1)];
const USER_COUNTER_FIELDS: [(&str, usize); 2] = [("following", 0), ("follower", 1)];

pub const POST_COUNTER_SCHEMA: CountSchema = CountSchema {
    name: "post_counter",
    slot_width_bytes: SLOT_WIDTH_BYTES,
    total_payload_bytes: SLOT_WIDTH_BYTES,
    fields: &POST_COUNTER_FIELDS,
};

pub const COMMENT_COUNTER_SCHEMA: CountSchema = CountSchema {
    name: "comment_counter",
    slot_width_bytes: SLOT_WIDTH_BYTES,
    total_payload_bytes: SLOT_WIDTH_BYTES * 2,
    fields: &COMMENT_COUNTER_FIELDS,
};

pub const USER_COUNTER_SCHEMA: CountSchema = CountSchema {
    name: "user_counter",
    slot_width_bytes: SLOT_WIDTH_BYTES,
    total_payload_bytes: SLOT_WIDTH_BYTES * 2,
    fields: &USER_COUNTER_FIELDS,
};

pub fn schema_for_key(key: &str) -> Option<&'static CountSchema> {
    if matches_family_key(key, "post") {
        return Some(&POST_COUNTER_SCHEMA);
    }
    if matches_family_key(key, "comment") {
        return Some(&COMMENT_COUNTER_SCHEMA);
    }
    if matches_family_key(key, "user") {
        return Some(&USER_COUNTER_SCHEMA);
    }
    None
}

fn matches_family_key(key: &str, family: &str) -> bool {
    let prefix = format!("count:{family}:{{");
    let Some(id_part) = key.strip_prefix(&prefix) else {
        return false;
    };
    let Some(inner) = id_part.strip_suffix('}') else {
        return false;
    };

    !inner.is_empty() && !inner.contains('{') && !inner.contains('}')
}
