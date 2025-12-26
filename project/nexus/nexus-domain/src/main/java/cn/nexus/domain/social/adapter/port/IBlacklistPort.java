package cn.nexus.domain.social.adapter.port;

/**
 * 黑名单查询端口。
 */
public interface IBlacklistPort {

    /**
     * 目标是否屏蔽来源。
     */
    boolean isBlocked(Long sourceId, Long targetId);
}
