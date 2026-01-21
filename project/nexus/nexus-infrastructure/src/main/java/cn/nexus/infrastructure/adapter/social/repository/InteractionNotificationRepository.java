package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IInteractionNotificationRepository;
import cn.nexus.domain.social.model.valobj.InteractionNotificationUpsertCmd;
import cn.nexus.domain.social.model.valobj.NotificationVO;
import cn.nexus.infrastructure.dao.social.IInteractionNotificationDao;
import cn.nexus.infrastructure.dao.social.po.InteractionNotificationPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 通知收件箱仓储 MyBatis 实现。
 *
 * @author codex
 * @since 2026-01-21
 */
@Repository
@RequiredArgsConstructor
public class InteractionNotificationRepository implements IInteractionNotificationRepository {

    private final IInteractionNotificationDao notificationDao;
    private final ISocialIdPort socialIdPort;

    @Override
    public List<NotificationVO> pageByUser(Long toUserId, String cursor, int limit) {
        if (toUserId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        Cursor c = Cursor.parse(cursor);
        List<InteractionNotificationPO> list = notificationDao.pageByUser(
                toUserId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<NotificationVO> res = new ArrayList<>(list.size());
        for (InteractionNotificationPO po : list) {
            NotificationVO vo = toVO(po);
            if (vo != null) {
                res.add(vo);
            }
        }
        return res;
    }

    @Override
    public void upsertIncrement(InteractionNotificationUpsertCmd cmd) {
        if (cmd == null
                || cmd.getToUserId() == null
                || cmd.getBizType() == null
                || cmd.getBizType().isBlank()
                || cmd.getTargetType() == null
                || cmd.getTargetType().isBlank()
                || cmd.getTargetId() == null) {
            return;
        }
        Long delta = cmd.getDelta();
        if (delta == null || delta == 0) {
            return;
        }
        Long notificationId = socialIdPort.nextId();
        notificationDao.upsertIncrement(
                notificationId,
                cmd.getToUserId(),
                cmd.getBizType(),
                cmd.getTargetType(),
                cmd.getTargetId(),
                cmd.getPostId(),
                cmd.getRootCommentId(),
                cmd.getLastActorUserId(),
                cmd.getLastCommentId(),
                delta);
    }

    @Override
    public void markRead(Long toUserId, Long notificationId) {
        if (toUserId == null || notificationId == null) {
            return;
        }
        notificationDao.markRead(toUserId, notificationId);
    }

    @Override
    public void markReadAll(Long toUserId) {
        if (toUserId == null) {
            return;
        }
        notificationDao.markReadAll(toUserId);
    }

    private NotificationVO toVO(InteractionNotificationPO po) {
        if (po == null) {
            return null;
        }
        Date update = po.getUpdateTime();
        return NotificationVO.builder()
                .createTime(update == null ? null : update.getTime())
                .notificationId(po.getNotificationId())
                .bizType(po.getBizType())
                .targetType(po.getTargetType())
                .targetId(po.getTargetId())
                .postId(po.getPostId())
                .rootCommentId(po.getRootCommentId())
                .lastCommentId(po.getLastCommentId())
                .lastActorUserId(po.getLastActorUserId())
                .unreadCount(po.getUnreadCount())
                .build();
    }

    private static final class Cursor {
        private final Date cursorTime;
        private final Long cursorId;

        private Cursor(Date cursorTime, Long cursorId) {
            this.cursorTime = cursorTime;
            this.cursorId = cursorId;
        }

        private static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.split(":");
            if (parts.length != 2) {
                return null;
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                return new Cursor(new Date(timeMs), id);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}

