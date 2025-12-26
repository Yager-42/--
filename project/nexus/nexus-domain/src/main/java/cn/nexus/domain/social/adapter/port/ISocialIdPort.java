package cn.nexus.domain.social.adapter.port;

/**
 * 社交领域通用ID与时间提供端口。
 */
public interface ISocialIdPort {
    /**
     * 生成全局唯一ID。
     */
    Long nextId();

    /**
     * 返回当前时间戳。
     */
    Long now();
}
