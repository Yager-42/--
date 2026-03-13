package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import java.util.List;

/**
 * 关系查询门面端口。
 *
 * <p>这里保留旧名字只是为了减少调用方改动，
 * 但语义已经收缩成“DB 真相源查询门面”，不再承担 Redis 邻接缓存、rebuild 或重建协议职责。</p>
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
