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

    /**
     * 统计某个用户的粉丝数量（反向表：谁关注了我）。
     *
     * <p>用于 fanout 大任务切片：dispatcher 需要用总数计算切片数量。</p>
     *
     * @param userId 被关注者 ID
     * @return 粉丝数量
     */
    int countFollowers(@Param("userId") Long userId);

    /**
     * 分页查询某个用户关注的对象 ID 列表（反向表：我关注了谁）。
     *
     * @param followerId 关注者 ID
     * @param offset     偏移量（从 0 开始）
     * @param limit      单页数量
     * @return 关注对象 ID 列表
     */
    java.util.List<Long> selectFollowingIds(@Param("followerId") Long followerId,
                                            @Param("offset") Integer offset,
                                            @Param("limit") Integer limit);
}
