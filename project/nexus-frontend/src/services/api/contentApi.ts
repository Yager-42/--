import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  CommentItemViewModel,
  CommentListResponseDTO,
  CommentReplyListResponseDTO,
  CommentReplyViewModel,
  ContentDetailResponseDTO,
  ContentHistoryResponseDTO,
  ContentRollbackPayload,
  ContentVersionViewModel,
  DeleteContentPayload,
  DraftSyncPayload,
  DraftSyncResponseDTO,
  OperationResultDTO,
  PostDetailViewModel,
  PublishAttemptResponseDTO,
  PublishContentPayload,
  PublishContentResponseDTO,
  SaveDraftPayload,
  SaveDraftResponseDTO,
  ScheduleAuditResponseDTO,
  ScheduleCancelPayload,
  ScheduleContentPayload,
  ScheduleContentResponseDTO,
  ScheduleUpdatePayload,
  UploadSessionPayload,
  UploadSessionResponseDTO
} from '@/types/content'

function mapCommentReply(item: CommentReplyListResponseDTO['items'][number]): CommentReplyViewModel {
  return {
    id: String(item.commentId),
    authorId: String(item.userId),
    authorName: item.nickname,
    body: item.content,
    createdAtLabel: new Date(item.createTime).toLocaleDateString('zh-CN'),
    canDelete: true
  }
}

function mapRootComment(
  item: CommentListResponseDTO['items'][number],
  options?: {
    isPinned?: boolean
  }
): CommentItemViewModel {
  return {
    id: String(item.root.commentId),
    authorId: String(item.root.userId),
    authorName: item.root.nickname,
    body: item.root.content,
    likeCountLabel: String(item.root.likeCount),
    replyCount: item.root.replyCount,
    repliesPreview: item.repliesPreview.map(mapCommentReply),
    isPinned: options?.isPinned ?? false,
    canPin: false,
    canDelete: true
  }
}

function mapContentVersion(item: ContentHistoryResponseDTO['versions'][number]): ContentVersionViewModel {
  return {
    id: String(item.versionId),
    title: item.title,
    contentPreview: item.content.slice(0, 120),
    timeLabel: new Date(item.time).toLocaleString('zh-CN')
  }
}

export async function fetchPostDetail(postId: string | number) {
  const response = await http.get<ApiEnvelope<ContentDetailResponseDTO>>(`/api/v1/content/${postId}`)
  const data = normalizeApiResponse(response.data)

  return {
    id: String(data.postId),
    authorId: String(data.authorId),
    authorName: data.authorNickname,
    authorAvatar: data.authorAvatarUrl,
    title: data.title,
    summary: data.summary,
    body: data.content,
    likeCountLabel: String(data.likeCount),
    publishTimeLabel: new Date(data.createTime).toLocaleDateString('zh-CN')
  } satisfies PostDetailViewModel
}

export async function fetchHotComments(postId: string | number) {
  const response = await http.get<ApiEnvelope<CommentListResponseDTO>>('/api/v1/comment/hot', {
    params: {
      postId
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    pinned: data.pinned ? mapRootComment(data.pinned, { isPinned: true }) : null,
    items: data.items.map((item) => mapRootComment(item)),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function fetchComments(postId: string | number) {
  const response = await http.get<ApiEnvelope<CommentListResponseDTO>>('/api/v1/comment/list', {
    params: {
      postId
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    comments: data.items.map((item) => mapRootComment(item)),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function fetchCommentReplies(rootId: string | number) {
  const response = await http.get<ApiEnvelope<CommentReplyListResponseDTO>>('/api/v1/comment/reply/list', {
    params: {
      rootId
    }
  })

  const data = normalizeApiResponse(response.data)

  return {
    items: data.items.map(mapCommentReply),
    nextCursor: data.nextCursor ?? ''
  }
}

export async function deleteComment(commentId: string | number) {
  const response = await http.delete<ApiEnvelope<OperationResultDTO>>(`/api/v1/comment/${commentId}`)
  return normalizeApiResponse(response.data)
}

export async function saveDraft(payload: SaveDraftPayload) {
  const response = await http.put<ApiEnvelope<SaveDraftResponseDTO>>('/api/v1/content/draft', payload)
  return normalizeApiResponse(response.data)
}

export async function syncDraft(draftId: string | number, payload: DraftSyncPayload) {
  const response = await http.patch<ApiEnvelope<DraftSyncResponseDTO>>(`/api/v1/content/draft/${draftId}`, payload)
  return normalizeApiResponse(response.data)
}

export async function publishPost(payload: PublishContentPayload) {
  const response = await http.post<ApiEnvelope<PublishContentResponseDTO>>('/api/v1/content/publish', payload)
  return normalizeApiResponse(response.data)
}

export async function schedulePost(payload: ScheduleContentPayload) {
  const response = await http.post<ApiEnvelope<ScheduleContentResponseDTO>>('/api/v1/content/schedule', payload)
  return normalizeApiResponse(response.data)
}

export async function updateScheduledPost(payload: ScheduleUpdatePayload) {
  const response = await http.patch<ApiEnvelope<OperationResultDTO>>('/api/v1/content/schedule', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchPublishAttempt(attemptId: string | number, userId: string | number) {
  const response = await http.get<ApiEnvelope<PublishAttemptResponseDTO>>(`/api/v1/content/publish/attempt/${attemptId}`, {
    params: {
      userId
    }
  })
  return normalizeApiResponse(response.data)
}

export async function fetchScheduleAudit(taskId: string | number, userId: string | number) {
  const response = await http.get<ApiEnvelope<ScheduleAuditResponseDTO>>(`/api/v1/content/schedule/${taskId}`, {
    params: {
      userId
    }
  })
  return normalizeApiResponse(response.data)
}

export async function cancelScheduledPost(payload: ScheduleCancelPayload) {
  const response = await http.post<ApiEnvelope<OperationResultDTO>>('/api/v1/content/schedule/cancel', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchContentHistory(postId: string | number) {
  const response = await http.get<ApiEnvelope<ContentHistoryResponseDTO>>(`/api/v1/content/${postId}/history`)
  const data = normalizeApiResponse(response.data)

  return {
    versions: data.versions.map(mapContentVersion),
    nextCursor: data.nextCursor ?? 0
  }
}

export async function rollbackPostVersion(postId: string | number, payload: ContentRollbackPayload) {
  const response = await http.post<ApiEnvelope<OperationResultDTO>>(`/api/v1/content/${postId}/rollback`, payload)
  return normalizeApiResponse(response.data)
}

export async function deletePost(postId: string | number, payload: DeleteContentPayload) {
  const response = await http.delete<ApiEnvelope<OperationResultDTO>>(`/api/v1/content/${postId}`, {
    data: payload
  })
  return normalizeApiResponse(response.data)
}

export async function createUploadSession(payload: UploadSessionPayload) {
  const response = await http.post<ApiEnvelope<UploadSessionResponseDTO>>('/api/v1/media/upload/session', payload)
  return normalizeApiResponse(response.data)
}
