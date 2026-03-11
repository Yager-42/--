package cn.nexus.infrastructure.dao.social;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFollowerDao {

    int insert(FollowerPO po);

    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /**
     * 仅供 Feed fanout 批处理切片扫描使用。
     *
     * <p>这里保留 offset 是为了内部写扩散扫描，不能复用于用户可见的 followers 列表分页。</p>
     */
    List<Long> selectFollowerIds(@Param("userId") Long userId,
                                 @Param("offset") Integer offset,
                                 @Param("limit") Integer limit);

    int countFollowers(@Param("userId") Long userId);
}
