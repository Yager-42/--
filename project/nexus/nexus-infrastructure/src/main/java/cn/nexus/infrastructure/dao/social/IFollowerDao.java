package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.FollowerPO;
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

    List<Long> selectFollowingIds(@Param("followerId") Long followerId,
                                  @Param("offset") Integer offset,
                                  @Param("limit") Integer limit);

    List<FollowerPO> selectFollowerRows(@Param("userId") Long userId,
                                        @Param("offset") Integer offset,
                                        @Param("limit") Integer limit);

    List<FollowerPO> selectFollowingRows(@Param("followerId") Long followerId,
                                         @Param("offset") Integer offset,
                                         @Param("limit") Integer limit);
}
