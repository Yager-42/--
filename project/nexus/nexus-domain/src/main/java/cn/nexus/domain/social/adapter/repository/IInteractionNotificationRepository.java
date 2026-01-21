package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.InteractionNotificationUpsertCmd;
import cn.nexus.domain.social.model.valobj.NotificationVO;

import java.util.List;

/**
 * 通知收件箱仓储接口：封装 MySQL interaction_notification 表读写。
 *
 * @author codex
 * @since 2026-01-21
 */
public interface IInteractionNotificationRepository {

    /**
     * 按用户分页读取通知（仅 unread_count > 0）。
     *
     * <p>游标格式："{updateTimeMs}:{notificationId}"；非法游标按空游标处理。</p>
     */
    List<NotificationVO> pageByUser(Long toUserId, String cursor, int limit);

    /**
     * 聚合写入：unread_count += delta（并刷新 last_* 与 update_time）。
     */
    void upsertIncrement(InteractionNotificationUpsertCmd cmd);

    /**
     * 标记单条已读：把 unread_count 置 0（幂等）。
     */
    void markRead(Long toUserId, Long notificationId);

    /**
     * 全部标记已读：对该用户批量把 unread_count 置 0（幂等）。
     */
    void markReadAll(Long toUserId);
}

