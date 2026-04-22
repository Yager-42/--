export type NotificationDTO = {
  title: string
  content: string
  createTime: number
  notificationId: number
  bizType: string
  targetType: string
  targetId: number
  postId: number
  rootCommentId?: number
  lastCommentId?: number
  unreadCount: number
}

export type NotificationListResponseDTO = {
  notifications: NotificationDTO[]
  nextCursor?: string
}

export type NotificationViewModel = {
  id: string
  actorName: string
  actionText: string
  unread: boolean
  timeLabel: string
  to?: {
    name: string
    params?: Record<string, string>
    query?: Record<string, string>
  }
}
