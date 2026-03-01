package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;

import java.util.Date;
import java.util.List;

/**
 * 内容/媒体仓储接口，抽象底层存储。
 */
public interface IContentRepository {

    ContentDraftEntity saveDraft(ContentDraftEntity draft);

    ContentDraftEntity findDraft(Long draftId);

    /**
     * 查询草稿并加行锁（用于版本号校验的并发安全覆盖写）。
     */
    ContentDraftEntity findDraftForUpdate(Long draftId);

    ContentPostEntity savePost(ContentPostEntity post);

    /**
     * 覆盖写入内容的帖子类型列表（业务类目/主题）。
     *
     * <p>语义：先删除旧映射，再批量插入新映射；postTypes 为空表示清空。</p>
     *
     * @param postId     内容 ID
     * @param postTypes  帖子类型列表（最多 5 个，调用方应先做归一化）
     */
    void replacePostTypes(Long postId, List<String> postTypes);

    ContentPostEntity findPost(Long postId);
    ContentPostEntity findPostForUpdate(Long postId);

    /**
     * 批量查询已发布内容（用于 timeline 批量回表）。
     *
     * @param postIds 内容 ID 列表
     * @return 内容列表（按入参 postIds 的顺序输出）
     */
    List<ContentPostEntity> listPostsByIds(List<Long> postIds);

    /**
     * 个人页分页查询已发布内容（按 createTime DESC, postId DESC）。
     *
     * @param userId 用户 ID
     * @param cursor 游标："{lastCreateTimeMs}:{lastPostId}"；为空表示从最新开始
     * @param limit  单页数量
     * @return 分页结果
     */
    ContentPostPageVO listUserPosts(Long userId, String cursor, int limit);

    /**
     * 仅更新内容状态（不修改版本号与正文）。
     *
     * <p>用于风控隔离（PENDING_REVIEW -> PUBLISHED/REJECTED）等“状态推进”场景。</p>
     *
     * @param postId          内容 ID
     * @param status          新状态
     * @param expectedStatus  期望的当前状态（用于并发幂等）
     * @return true=本次完成状态推进；false=状态不匹配或不存在
     */
    boolean updatePostStatus(Long postId, Integer status, Integer expectedStatus);

    /**
     * 状态推进（带版本号约束）。
     *
     * <p>用于“人审回写”这类场景：必须绑定到 attempt 对应的版本号，避免推进错版本。</p>
     */
    boolean updatePostStatusIfMatchVersion(Long postId, Integer status, Integer expectedStatus, Integer expectedVersion);

    /**
     * 更新内容摘要（AI 异步写回）。
     *
     * @param postId         内容 ID
     * @param summary        摘要文本（可为空）
     * @param summaryStatus  摘要状态：0未生成/1已生成/2失败
     * @return true=本次更新成功；false=目标不存在
     */
    boolean updatePostSummary(Long postId, String summary, Integer summaryStatus);

    boolean updatePostStatusAndContent(Long postId, Integer status, Integer versionNum, Boolean edited,
                                       String contentText, String mediaInfo, String locationInfo, Integer visibility);

    void saveHistory(ContentHistoryEntity history);

    List<ContentHistoryEntity> listHistory(Long postId, Integer limit, Integer offset);

    ContentHistoryEntity findHistoryVersion(Long postId, Integer versionNum);

    boolean softDelete(Long postId, Long userId);

    /**
     * 软删（带状态/版本条件）：用于 delete 与 publish 并发时的防御，避免覆盖其它版本更新结果。
     */
    boolean softDeleteIfMatchStatusAndVersion(Long postId, Integer expectedStatus, Integer expectedVersion, Long deleteTimeMs);

    /**
     * 物理清理：删除超过指定时间的软删内容（分批）。
     *
     * <p>注意：仅清理 content_post 与关联的 content_post_type；content_history 保留审计，不做物理删除。</p>
     *
     * @param cutoff delete_time 早于该时间的记录会被清理
     * @param limit  单次最多清理条数
     * @return 实际清理的 content_post 行数
     */
    int deleteSoftDeletedBefore(Date cutoff, int limit);

    ContentScheduleEntity createSchedule(ContentScheduleEntity schedule);

    boolean updateScheduleStatus(Long taskId, Integer status, Integer retryCount, String lastError, Integer alarmSent, Long nextScheduleTime, Integer expectedStatus);

    java.util.List<ContentScheduleEntity> listPendingSchedules(Long beforeTime, Integer limit);

    ContentScheduleEntity findSchedule(Long taskId);

    boolean cancelSchedule(Long taskId, Long userId, String reason);

    ContentScheduleEntity findScheduleByToken(String token);
    boolean updateSchedule(Long taskId, Long userId, Long scheduleTime, String contentData, String idempotentToken, String reason);

    ContentScheduleEntity findActiveScheduleByPostId(Long postId);

}
