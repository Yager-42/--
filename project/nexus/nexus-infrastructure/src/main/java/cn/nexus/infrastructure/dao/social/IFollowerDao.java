package cn.nexus.infrastructure.dao.social;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFollowerDao {

    int insert(FollowerPO po);

    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    List<Long> selectFollowerIds(@Param("userId") Long userId,
                                 @Param("offset") Integer offset,
                                 @Param("limit") Integer limit);

    int countFollowers(@Param("userId") Long userId);
}
