package cn.nexus.domain.social.adapter.port;

/**
 * 内容域事件 Outbox 端口：负责事务内落库与事务提交后的投递尝试。
 *
 * <p>用于解决：写库成功但 MQ 投递失败导致的“下游不一致”。</p>
 */
public interface IContentEventOutboxPort {

    /**
     * 事务内保存“发布成功”事件到 outbox（幂等去重由实现负责）。
     */
    void savePostPublished(Long postId, Long userId, Integer versionNum, Long tsMs);

    /**
     * 事务内保存“内容更新”事件到 outbox（幂等去重由实现负责）。
     */
    void savePostUpdated(Long postId, Long operatorId, Integer versionNum, Long tsMs);

    /**
     * 事务内保存“内容删除”事件到 outbox（幂等去重由实现负责）。
     */
    void savePostDeleted(Long postId, Long operatorId, Integer versionNum, Long tsMs);

    /**
     * 事务内保存“摘要生成”事件到 outbox（幂等去重由实现负责）。
     */
    void savePostSummaryGenerate(Long postId, Long operatorId, Integer versionNum, Long tsMs);

    /**
     * 尝试投递待发送的 outbox 事件（NEW/FAIL）；失败则留待重试。
     */
    void tryPublishPending();

    /**
     * 清理已完成事件（SENT）在指定时间之前的记录。
     *
     * @param beforeTime 截止时间（包含该时间之前）
     * @return 删除行数
     */
    int cleanDoneBefore(java.util.Date beforeTime);
}
