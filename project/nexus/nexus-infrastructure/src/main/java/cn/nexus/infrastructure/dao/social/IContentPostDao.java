package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentPostDao {

    int insert(ContentPostPO po);

    ContentPostPO selectById(@Param("postId") Long postId);

    int updateStatus(@Param("postId") Long postId, @Param("status") Integer status);

    int updateStatusIfMatch(@Param("postId") Long postId, @Param("status") Integer status, @Param("expectedStatus") Integer expectedStatus);

    int updateStatusIfMatchAndVersion(@Param("postId") Long postId,
                                      @Param("status") Integer status,
                                      @Param("expectedStatus") Integer expectedStatus,
                                      @Param("expectedVersion") Integer expectedVersion);

    int softDeleteIfMatchAndVersion(@Param("postId") Long postId,
                                    @Param("deleteTime") java.util.Date deleteTime,
                                    @Param("expectedStatus") Integer expectedStatus,
                                    @Param("expectedVersion") Integer expectedVersion);

    int updateStatusWithUser(@Param("postId") Long postId, @Param("userId") Long userId, @Param("status") Integer status);

    ContentPostPO selectByIdForUpdate(@Param("postId") Long postId);

    /**
     * 更新内容摘要（AI 异步写回）。
     */
    int updateSummary(@Param("postId") Long postId,
                      @Param("summary") String summary,
                      @Param("summaryStatus") Integer summaryStatus);

    /**
     * 查询超过指定时间的软删内容（用于定时物理清理）。仅返回 post_id，避免一次查询拉取大字段。
     */
    java.util.List<Long> selectSoftDeletedIdsBefore(@Param("cutoff") java.util.Date cutoff, @Param("limit") Integer limit);

    /**
     * 按 postId 批量物理删除（仅删除软删且超过 cutoff 的记录）。
     */
    int deleteSoftDeletedByIds(@Param("postIds") java.util.List<Long> postIds, @Param("cutoff") java.util.Date cutoff);

    int updateContentAndVersion(@Param("postId") Long postId,
                                @Param("contentText") String contentText,
                                @Param("mediaInfo") String mediaInfo,
                                @Param("locationInfo") String locationInfo,
                                @Param("versionNum") Integer versionNum,
                                @Param("isEdited") Integer isEdited,
                                @Param("status") Integer status,
                                @Param("visibility") Integer visibility,
                                @Param("expectedVersion") Integer expectedVersion);

    /**
     * 批量查询已发布内容（用于 timeline 批量回表）。
     *
     * @param postIds 内容 ID 列表
     * @return 内容列表（顺序不保证）
     */
    java.util.List<ContentPostPO> selectByIds(@Param("postIds") java.util.List<Long> postIds);

    /**
     * 个人页分页查询已发布内容（按 create_time DESC, post_id DESC）。
     *
     * @param userId        作者用户 ID
     * @param cursorTime    游标时间（create_time），为空表示从最新开始
     * @param cursorPostId  游标 postId，需与 cursorTime 同时传入
     * @param limit         单页数量
     * @return 内容列表（按时间倒序）
     */
    java.util.List<ContentPostPO> selectByUserPage(@Param("userId") Long userId,
                                                   @Param("cursorTime") java.util.Date cursorTime,
                                                   @Param("cursorPostId") Long cursorPostId,
                                                   @Param("limit") Integer limit);

    /**
     * 全站分页查询已发布内容（用于推荐冷启动回灌）。
     *
     * @param cursorTime    游标时间（create_time），为空表示从最新开始
     * @param cursorPostId  游标 postId，需与 cursorTime 同时传入
     * @param limit         单页数量
     * @return 内容列表（按时间倒序）
     */
    java.util.List<ContentPostPO> selectPublishedPage(@Param("cursorTime") java.util.Date cursorTime,
                                                      @Param("cursorPostId") Long cursorPostId,
                                                      @Param("limit") Integer limit);
}
