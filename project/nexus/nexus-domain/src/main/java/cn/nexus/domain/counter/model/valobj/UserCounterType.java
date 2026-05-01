package cn.nexus.domain.counter.model.valobj;

/**
 * 用户维度计数类型。
 *
 * @author codex
 * @since 2026-04-02
 */
public enum UserCounterType {

    FOLLOWINGS("followings"),
    FOLLOWERS("followers"),
    POSTS("posts"),
    LIKES_RECEIVED("likesReceived"),
    FAVS_RECEIVED("favsReceived");

    private final String code;

    UserCounterType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
