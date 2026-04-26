package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.Class2CounterProjectionStatePO;
import java.util.Date;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IClass2CounterProjectionStateDao {

    Class2CounterProjectionStatePO selectForUpdate(@Param("projectionKey") String projectionKey);

    int insertIgnore(@Param("projectionKey") String projectionKey,
                     @Param("projectionType") String projectionType,
                     @Param("lastVersion") Long lastVersion,
                     @Param("updateTime") Date updateTime);

    Class2CounterProjectionStatePO selectOne(@Param("projectionKey") String projectionKey);

    int updateVersion(@Param("projectionKey") String projectionKey,
                      @Param("projectionType") String projectionType,
                      @Param("lastVersion") Long lastVersion,
                      @Param("updateTime") Date updateTime);
}
