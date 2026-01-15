package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.LikeCountPO;
import cn.nexus.infrastructure.dao.social.po.LikeTargetPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ILikeCountDao {

    LikeCountPO selectOne(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    List<LikeCountPO> selectByTargets(@Param("targets") List<LikeTargetPO> targets);

    int upsert(@Param("po") LikeCountPO po);
}

