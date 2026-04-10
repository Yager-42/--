#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CountRedisError {
    NotImplemented,
    UnknownKeyFamily,
    UnknownField,
    UnknownStateFamily,
    StateCountMismatch,
    CountOverflow,
    CountNegativeValue,
    InvalidDesiredState,
    ReactionCorruptState,
    WrongArity,
    InvalidInteger,
}

impl CountRedisError {
    pub fn message(self) -> &'static str {
        match self {
            Self::NotImplemented => "not implemented",
            Self::UnknownKeyFamily => "ERR UNKNOWN_KEY_FAMILY",
            Self::UnknownField => "ERR UNKNOWN_FIELD",
            Self::UnknownStateFamily => "ERR UNKNOWN_STATE_FAMILY",
            Self::StateCountMismatch => "ERR STATE_COUNT_MISMATCH",
            Self::CountOverflow => "ERR COUNT_OVERFLOW",
            Self::CountNegativeValue => "ERR COUNT_NEGATIVE_VALUE",
            Self::InvalidDesiredState => "ERR INVALID_DESIRED_STATE",
            Self::ReactionCorruptState => "ERR REACTION_CORRUPT_STATE",
            Self::WrongArity => "ERR wrong number of arguments",
            Self::InvalidInteger => "ERR value is not an integer or out of range",
        }
    }
}
