package cn.nexus.domain.user.adapter.port;

/**
 * 用户域事件 `Outbox` 端口：负责事务内落库和事务提交后的投递尝试。
 *
 * <p>领域层只依赖这个端口，不直接碰 MQ 或数据库客户端。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
public interface IUserEventOutboxPort {

    /**
     * 在事务内保存“昵称变更”事件到 `outbox`。
     *
     * <p>幂等去重细节由具体实现负责。</p>
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param tsMs 事件时间戳（毫秒），类型：{@link Long}
     */
    void saveNicknameChanged(Long userId, Long tsMs);

    /**
     * 尝试投递待发送的 `outbox` 事件。
     *
     * <p>通常会处理 `NEW / FAIL` 两种状态；失败时保留给定时任务重试。</p>
     */
    void tryPublishPending();

    /**
     * 清理已完成事件（DONE）在指定时间之前的记录。
     *
     * @param beforeTime 截止时间（包含该时间之前），类型：{@link java.util.Date}
     * @return 删除行数，类型：{@code int}
     */
    int cleanDoneBefore(java.util.Date beforeTime);
}
