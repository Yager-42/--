import type { FeedCardViewModel } from '@/types/viewModels'

export type OperationResultDTO = {
  success: boolean
  id?: number
  status?: string
  message?: string
}

export type ContentDetailResponseDTO = {
  postId: number
  authorId: number
  authorNickname: string
  authorAvatarUrl?: string
  title: string
  content: string
  summary: string
  createTime: number
  likeCount: number
}

export type CommentViewDTO = {
  commentId: number
  postId: number
  userId: number
  nickname: string
  avatarUrl?: string
  rootId: number
  parentId: number
  replyToId: number
  content: string
  status: number
  likeCount: number
  replyCount: number
  createTime: number
}

export type RootCommentViewDTO = {
  root: CommentViewDTO
  repliesPreview: CommentViewDTO[]
}

export type CommentListResponseDTO = {
  pinned?: RootCommentViewDTO
  items: RootCommentViewDTO[]
  nextCursor?: string
}

export type CommentReplyListResponseDTO = {
  items: CommentViewDTO[]
  nextCursor?: string
}

export type CommentRequestPayload = {
  postId: number
  parentId?: number
  content: string
  commentId?: number
}

export type CommentResponseDTO = {
  commentId: number
  createTime: number
  status: string
}

export type PinCommentPayload = {
  commentId: number
  postId: number
}

export type ReactionPayload = {
  requestId: string
  targetId: number
  targetType: string
  type: string
  action: string
}

export type ReactionResponseDTO = {
  requestId: string
  currentCount: number
  success: boolean
}

export type ReactionStateResponseDTO = {
  state: boolean
  currentCount: number
}

export type SaveDraftPayload = {
  draftId?: number
  userId: number
  title: string
  contentText: string
  mediaIds: string[]
}

export type SaveDraftResponseDTO = {
  draftId: number
}

export type DraftSyncPayload = {
  draftId: number
  title: string
  diffContent: string
  clientVersion: number
  deviceId: string
  mediaIds: string[]
}

export type DraftSyncResponseDTO = {
  serverVersion: string
  syncTime: number
}

export type PublishContentPayload = {
  postId?: number
  userId: number
  title: string
  text: string
  mediaInfo: string
  location: string
  visibility: string
  postTypes: string[]
}

export type PublishContentResponseDTO = {
  postId: number
  attemptId: number
  versionNum: number
  status: string
}

export type ScheduleContentPayload = {
  postId: number
  publishTime: number
  timezone: string
}

export type ScheduleContentResponseDTO = {
  taskId: number
  postId: number
  status: string
}

export type PublishAttemptResponseDTO = {
  attemptId: number
  postId: number
  userId: number
  idempotentToken: string
  transcodeJobId: string
  attemptStatus: string
  riskStatus: string
  transcodeStatus: string
  publishedVersionNum: number
  errorCode: string
  errorMessage: string
  createTime: number
  updateTime: number
}

export type ScheduleAuditResponseDTO = {
  taskId: number
  userId: number
  scheduleTime: number
  status: string
  retryCount: number
  isCanceled: boolean
  lastError: string
  alarmSent: boolean
  contentData: string
}

export type ScheduleUpdatePayload = {
  taskId: number
  userId: number
  publishTime: number
  contentData: string
  reason: string
}

export type ScheduleCancelPayload = {
  taskId: number
  userId: number
  reason: string
}

export type DeleteContentPayload = {
  userId: number
  postId: number
}

export type ContentRollbackPayload = {
  postId: number
  userId: number
  targetVersionId: number
}

export type ContentVersionDTO = {
  versionId: number
  title: string
  content: string
  time: number
}

export type ContentHistoryResponseDTO = {
  versions: ContentVersionDTO[]
  nextCursor: number
  versionId?: number
  title?: string
  content?: string
  time?: number
}

export type UploadSessionPayload = {
  fileType: string
  fileSize: number
  crc32: string
}

export type UploadSessionResponseDTO = {
  uploadUrl: string
  token: string
  sessionId: string
}

export type CommentReplyViewModel = {
  id: string
  authorId: string
  authorName: string
  body: string
  createdAtLabel?: string
  canDelete?: boolean
}

export type CommentItemViewModel = {
  id: string
  authorId: string
  authorName: string
  body: string
  likeCountLabel?: string
  replyCount?: number
  repliesPreview?: CommentReplyViewModel[]
  replies?: CommentReplyViewModel[]
  isPinned?: boolean
  canPin?: boolean
  canDelete?: boolean
}

export type PostDetailViewModel = FeedCardViewModel & {
  body?: string
  title?: string
}

export type ContentVersionViewModel = {
  id: string
  title: string
  contentPreview: string
  timeLabel: string
}

export type UploadSessionViewModel = {
  uploadUrl: string
  token: string
  sessionId: string
}
