package cn.nexus.domain.user.adapter.port;

/**
 * 用户域事件 Outbox 端口：负责事务内落库与事务提交后的投递尝试。
 *
 * <p>注意：领域层只依赖端口，不直接依赖 MQ/DB 客户端。</p>
 */
public interface IUserEventOutboxPort {

    /**
     * 事务内保存“昵称变更”事件到 outbox（幂等去重由实现负责）。
     *
     * @param userId 用户 ID
     * @param tsMs   事件时间戳（毫秒）
     */
    void saveNicknameChanged(Long userId, Long tsMs);

    /**
     * 尝试投递待发送的 outbox 事件（NEW/FAIL）；失败则留待重试。
     */
    void tryPublishPending();

    /**
     * 清理已完成事件（DONE）在指定时间之前的记录。
     *
     * @param beforeTime 截止时间（包含该时间之前）
     * @return 删除行数
     */
    int cleanDoneBefore(java.util.Date beforeTime);
}
