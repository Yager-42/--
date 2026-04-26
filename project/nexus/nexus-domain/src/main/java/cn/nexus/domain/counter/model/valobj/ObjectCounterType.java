package cn.nexus.domain.counter.model.valobj;

/**
 * 对象维度计数类型。
 *
 * @author codex
 * @since 2026-04-02
 */
public enum ObjectCounterType {

    LIKE("like");

    private final String code;

    ObjectCounterType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
