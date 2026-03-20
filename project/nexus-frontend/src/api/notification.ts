import http from '@/utils/http';
import type { ApiResponse, OperationResultDTO } from './types';

export interface NotificationDTO {
  notificationId: string;
  type: 'LIKE' | 'COMMENT' | 'FOLLOW';
  senderId: string;
  senderName: string;
  senderAvatar: string;
  content: string;
  targetId?: string; // e.g. postId
  isRead: boolean;
  createTime: number;
}

export interface NotificationListResponseDTO {
  notifications: NotificationDTO[];
  nextCursor: string;
}

export const fetchNotifications = (params: { cursor?: string, limit?: number }) => {
  return http.get<ApiResponse<NotificationListResponseDTO>>('/notification/list', { params });
}

export const markAsRead = (notificationId: string) => {
  return http.post<ApiResponse<OperationResultDTO>>('/notification/read', { notificationId });
}

export const markAllAsRead = () => {
  return http.post<ApiResponse<OperationResultDTO>>('/notification/read/all');
}
