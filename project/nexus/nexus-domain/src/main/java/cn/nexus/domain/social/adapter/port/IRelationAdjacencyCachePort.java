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
