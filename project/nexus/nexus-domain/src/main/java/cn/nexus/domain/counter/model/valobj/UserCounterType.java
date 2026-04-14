package cn.nexus.domain.counter.model.valobj;

/**
 * 用户维度计数类型。
 *
 * @author codex
 * @since 2026-04-02
 */
public enum UserCounterType {

    FOLLOWING("following"),
    FOLLOWER("follower"),
    POST("post"),
    LIKE_RECEIVED("like_received"),
    FAVORITE_RECEIVED("favorite_received");

    private final String code;

    UserCounterType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
