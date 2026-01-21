package cn.nexus.domain.social.adapter.port;

/**
 * 通知事件 MQ 收件箱持久化端口：用于消费者幂等去重与状态标记。
 *
 * @author codex
 * @since 2026-01-21
 */
public interface IInteractionNotifyInboxPort {

    /**
     * 按 eventId 入库，若已存在则返回 false。
     */
    boolean save(String eventId, String eventType, String payload);

    /**
     * 处理成功标记 DONE。
     */
    void markDone(String eventId);

    /**
     * 处理失败标记 FAIL。
     */
    void markFail(String eventId);
}

