package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.FollowerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFollowerDao {

    int insert(FollowerPO po);

    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    /**
     * 分页查询某个用户的粉丝 ID 列表（反向表：谁关注了我）。
     *
     * @param userId  被关注者 ID
     * @param offset 偏移量（从 0 开始）
     * @param limit  单页数量
     * @return 粉丝 ID 列表
     */
    java.util.List<Long> selectFollowerIds(@Param("userId") Long userId,
                                           @Param("offset") Integer offset,
                                           @Param("limit") Integer limit);
}
