package cn.nexus.domain.social.adapter.port;

/**
 * 评论相关 MQ 事件幂等收件箱端口：用于“至少一次投递”下的去重，避免计数/热榜被重复累加。
 *
 * <p>注意：这里的语义是“事件已应用”的幂等锁；只有当 save 返回 true 时，才允许继续执行业务写入。</p>
 *
 * @author codex
 * @since 2026-01-22
 */
public interface IInteractionCommentInboxPort {

    /**
     * 按 eventId 入库，若已存在则返回 false。
     */
    boolean save(String eventId, String eventType, String payload);
}

