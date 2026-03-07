package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import java.util.List;

public interface IRelationAdjacencyCachePort {

    void addFollow(Long sourceId, Long targetId, Long followTimeMs);

    void removeFollow(Long sourceId, Long targetId);

    List<Long> listFollowing(Long sourceId, int limit);

    List<Long> listFollowers(Long targetId, int limit);

    List<RelationUserEdgeVO> pageFollowing(Long sourceId, String cursor, int limit);

    List<RelationUserEdgeVO> pageFollowers(Long targetId, String cursor, int limit);

    void rebuildFollowing(Long sourceId);

    void rebuildFollowers(Long targetId);

    void evict(Long userId);
}
