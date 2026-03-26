import http from '@/utils/http';
import type { CursorPageMeta, OperationResultDTO } from './types';

type NotificationBizType = 'LIKE' | 'COMMENT' | 'FOLLOW' | 'SYSTEM';

interface RawNotificationDTO {
  title: string;
  content: string;
  createTime: number;
  notificationId: number | string;
  bizType: string;
  targetType?: string;
  targetId?: number | string | null;
  postId?: number | string | null;
  rootCommentId?: number | string | null;
  lastCommentId?: number | string | null;
  lastActorUserId?: number | string | null;
  unreadCount?: number;
}

interface RawNotificationListResponseDTO {
  notifications: RawNotificationDTO[];
  nextCursor?: string | null;
}

export interface NotificationDTO {
  notificationId: string;
  type: NotificationBizType;
  senderId: string;
  senderName: string;
  senderAvatar: string;
  content: string;
  targetId: string;
  hasUnread: boolean;
  isRead: boolean;
  createTime: number;
}

export interface NotificationListResponseDTO {
  notifications: NotificationDTO[];
  page: CursorPageMeta;
}

export interface NotificationListRequestDTO {
  userId: string;
  cursor?: string;
}

const PLACEHOLDER_AVATAR = 'https://via.placeholder.com/80';

const toNullableCursor = (value?: string | null): string | null => {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const normalizeNotificationType = (bizType: string): NotificationBizType => {
  const normalized = bizType.toUpperCase();
  if (normalized.includes('FOLLOW')) {
    return 'FOLLOW';
  }
  if (normalized.includes('COMMENT') || normalized.includes('REPLY')) {
    return 'COMMENT';
  }
  if (normalized.includes('LIKE') || normalized.includes('FAV')) {
    return 'LIKE';
  }
  return 'SYSTEM';
};

const toTargetId = (item: RawNotificationDTO): string => {
  return String(item.targetId ?? item.postId ?? item.rootCommentId ?? '');
};

const mapNotification = (item: RawNotificationDTO): NotificationDTO => {
  const unreadCount = Number(item.unreadCount ?? 0);

  return {
    notificationId: String(item.notificationId),
    type: normalizeNotificationType(item.bizType),
    senderId: String(item.lastActorUserId ?? ''),
    senderName: item.title || '系统通知',
    senderAvatar: PLACEHOLDER_AVATAR,
    content: item.content || '',
    targetId: toTargetId(item),
    hasUnread: unreadCount > 0,
    isRead: unreadCount === 0,
    createTime: Number(item.createTime ?? 0)
  };
};

export const fetchNotifications = async (
  params: NotificationListRequestDTO
): Promise<NotificationListResponseDTO> => {
  const response = await http.get<RawNotificationListResponseDTO>('/notification/list', { params });
  const nextCursor = toNullableCursor(response.nextCursor);
  const seen = new Set<string>();
  const notifications = response.notifications
    .map(mapNotification)
    .filter((item) => {
      if (seen.has(item.notificationId)) {
        return false;
      }
      seen.add(item.notificationId);
      return true;
    });

  return {
    notifications,
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};

export const markAsRead = (notificationId: string): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/notification/read', { notificationId });
};

export const markAllAsRead = (): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/notification/read/all');
};
