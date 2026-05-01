package cn.nexus.domain.counter.model.valobj;

/**
 * 对象维度计数类型。
 *
 * @author codex
 * @since 2026-04-02
 */
public enum ObjectCounterType {

    LIKE("like"),
    FAV("fav");

    private final String code;

    ObjectCounterType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ObjectCounterType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ObjectCounterType type : values()) {
            if (type.code.equals(code.trim())) {
                return type;
            }
        }
        return null;
    }
}
