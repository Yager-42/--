package cn.nexus.domain.social.adapter.port;

/**
 * 好友请求幂等端口：用于防止重复提交/插入。
 */
public interface IFriendRequestIdempotentPort {

    /**
     * 尝试占用幂等键。
     *
     * @param key 幂等键（通常 source-target）
     * @param ttlSeconds 过期时间（秒）
     * @return true 表示占用成功，可继续处理；false 表示已存在，视为重复请求
     */
    boolean acquire(String key, long ttlSeconds);
}
