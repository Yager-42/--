package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.LikePO;
import cn.nexus.infrastructure.dao.social.po.LikeTargetPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ILikeDao {

    Integer selectStatus(@Param("userId") Long userId,
                         @Param("targetType") String targetType,
                         @Param("targetId") Long targetId);

    List<LikePO> selectLikedTargets(@Param("userId") Long userId,
                                    @Param("targets") List<LikeTargetPO> targets);

    int batchUpsert(@Param("list") List<LikePO> list);
}

