import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type { NotificationListResponseDTO, NotificationViewModel } from '@/types/notification'

function buildNotificationRoute(item: NotificationListResponseDTO['notifications'][number]): NotificationViewModel['to'] {
  const postId = item.postId || (item.targetType === 'POST' ? item.targetId : 0)

  if (!postId) {
    return undefined
  }

  const isCommentNotification = ['POST_COMMENTED', 'COMMENT_REPLIED', 'COMMENT_MENTIONED'].includes(item.bizType)
  const query: Record<string, string> = {}

  if (isCommentNotification || item.targetType === 'COMMENT') {
    query.focus = 'comments'

    if (item.rootCommentId) {
      query.rootCommentId = String(item.rootCommentId)
    }

    if (item.lastCommentId) {
      query.commentId = String(item.lastCommentId)
    }
  }

  return {
    name: 'post-detail',
    params: {
      id: String(postId)
    },
    ...(Object.keys(query).length
      ? {
          query
        }
      : {})
  }
}

export async function fetchNotifications(cursor?: string) {
  const response = await http.get<ApiEnvelope<NotificationListResponseDTO>>('/api/v1/notification/list', {
    params: {
      cursor
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    notifications: data.notifications.map<NotificationViewModel>((item) => ({
      id: String(item.notificationId),
      actorName: item.title,
      actionText: item.content,
      unread: item.unreadCount > 0,
      timeLabel: new Date(item.createTime).toLocaleDateString('zh-CN'),
      to: buildNotificationRoute(item)
    })),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function markNotificationRead(notificationId: string | number) {
  const response = await http.post<ApiEnvelope<{ success: boolean }>>('/api/v1/notification/read', {
    notificationId
  })

  return normalizeApiResponse(response.data)
}

export async function markAllNotificationsRead() {
  const response = await http.post<ApiEnvelope<{ success: boolean }>>('/api/v1/notification/read/all')
  return normalizeApiResponse(response.data)
}
