package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import java.util.List;

/**
 * 关系查询门面端口。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
public interface IRelationAdjacencyCachePort {

    void addFollow(Long sourceId, Long targetId, Long followTimeMs);

    void removeFollow(Long sourceId, Long targetId);

    /**
     * 添加关注邻接并设置固定 TTL（秒）。
     *
     * <p>用于关系投影消费链路，保障 `uf:flws:*` / `uf:fans:*` 的 2h 生命周期语义。</p>
     */
    void addFollowWithTtl(Long sourceId, Long targetId, Long followTimeMs, long ttlSeconds);

    /**
     * 移除关注邻接并刷新固定 TTL（秒）。
     */
    void removeFollowWithTtl(Long sourceId, Long targetId, long ttlSeconds);

    List<Long> listFollowing(Long sourceId, int limit);

    List<Long> listFollowers(Long targetId, int limit);

    List<RelationUserEdgeVO> pageFollowing(Long sourceId, String cursor, int limit);

    List<RelationUserEdgeVO> pageFollowers(Long targetId, String cursor, int limit);

    /**
     * 兼容保留的清理入口。
     *
     * <p>当前实现下这里默认是空操作，不能再触发任何 rebuild 语义。</p>
     */
    void evict(Long userId);
}
