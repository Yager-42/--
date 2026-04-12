import http from '@/utils/http';
import type { CursorPageResult, OperationResultDTO } from './types';

interface RawContentDetailResponseDTO {
  postId: number | string;
  authorId: number | string;
  authorNickname: string;
  authorAvatarUrl?: string;
  title?: string;
  content?: string;
  summary?: string;
  summaryStatus?: number;
  mediaType?: number;
  mediaInfo?: string | null;
  locationInfo?: string;
  status?: number;
  visibility?: number;
  versionNum?: number;
  edited?: boolean;
  createTime?: number;
  likeCount?: number;
}

export interface ContentDetailViewModel {
  postId: string;
  authorId: string;
  authorName: string;
  authorAvatar: string;
  title: string;
  content: string;
  summary: string;
  mediaType: number;
  mediaUrls: string[];
  locationInfo: string;
  status: number;
  visibility: number;
  versionNum: number;
  edited: boolean;
  createTime: number;
  likeCount: number;
}

export interface PublishContentRequestDTO {
  postId?: string;
  userId?: string;
  title: string;
  text: string;
  mediaInfo: string;
  visibility: string;
  location?: string;
  postTypes?: string[];
}

export interface PublishContentResponseDTO {
  postId: string;
  attemptId: string;
  versionNum: number;
  status: string;
}

export interface SaveDraftRequestDTO {
  draftId?: string;
  userId?: string;
  title: string;
  contentText: string;
  mediaIds: string[];
}

export interface SaveDraftResponseDTO {
  draftId: string;
}

interface RawDraftSyncResponseDTO {
  serverVersion: string;
  syncTime: number;
}

export interface DraftSyncRequestDTO {
  draftId?: string;
  title?: string;
  diffContent?: string;
  clientVersion?: number;
  deviceId?: string;
  mediaIds?: string[];
}

export interface DraftSyncResponseDTO {
  serverVersion: string;
  syncTime: number;
}

interface RawPublishAttemptResponseDTO {
  attemptId: number | string;
  postId: number | string;
  userId: number | string;
  idempotentToken?: string;
  transcodeJobId?: string;
  attemptStatus?: number;
  riskStatus?: number;
  transcodeStatus?: number;
  publishedVersionNum?: number;
  errorCode?: string;
  errorMessage?: string;
  createTime?: number;
  updateTime?: number;
}

export interface PublishAttemptResponseDTO {
  attemptId: string;
  postId: string;
  userId: string;
  idempotentToken: string;
  transcodeJobId: string;
  attemptStatus: number;
  riskStatus: number;
  transcodeStatus: number;
  publishedVersionNum: number;
  errorCode: string;
  errorMessage: string;
  createTime: number;
  updateTime: number;
}

interface RawScheduleContentResponseDTO {
  taskId: number | string;
  postId: number | string;
  status: string;
}

export interface ScheduleContentRequestDTO {
  postId: string;
  publishTime: number;
  timezone: string;
}

export interface ScheduleContentResponseDTO {
  taskId: string;
  postId: string;
  status: string;
}

export interface ScheduleUpdateRequestDTO {
  taskId: string;
  userId?: string;
  publishTime?: number;
  contentData?: string;
  reason?: string;
}

export interface ScheduleCancelRequestDTO {
  taskId: string;
  userId?: string;
  reason?: string;
}

interface RawScheduleAuditResponseDTO {
  taskId: number | string;
  userId: number | string;
  scheduleTime?: number;
  status?: number;
  retryCount?: number;
  isCanceled?: number;
  lastError?: string;
  alarmSent?: number;
  contentData?: string;
}

export interface ScheduleAuditResponseDTO {
  taskId: string;
  userId: string;
  scheduleTime: number;
  status: number;
  retryCount: number;
  isCanceled: number;
  lastError: string;
  alarmSent: number;
  contentData: string;
}

interface RawContentHistoryVersionDTO {
  versionId: number | string;
  title?: string;
  content?: string;
  time?: number;
}

interface RawContentHistoryResponseDTO {
  versions: RawContentHistoryVersionDTO[];
  nextCursor?: number | string | null;
}

export interface ContentHistoryVersionDTO {
  versionId: string;
  title: string;
  content: string;
  time: number;
}

export interface ContentHistoryRequestDTO {
  userId?: string;
  limit?: number;
  offset?: number;
}

export interface ContentRollbackRequestDTO {
  postId?: string;
  userId?: string;
  targetVersionId: string;
}

export interface DeleteContentRequestDTO {
  userId?: string;
  postId?: string;
}

export interface UploadSessionRequestDTO {
  fileType: string;
  fileSize: number;
  crc32?: string;
}

export interface UploadSessionResponseDTO {
  uploadUrl: string;
  token: string;
  sessionId: string;
}

const parseMediaInfo = (mediaInfo?: string | null): string[] => {
  if (!mediaInfo) {
    return [];
  }

  const trimmed = mediaInfo.trim();
  if (!trimmed) {
    return [];
  }

  const parseJsonValue = (value: unknown): string[] => {
    if (Array.isArray(value)) {
      return value
        .map((item) => {
          if (typeof item === 'string') {
            return item.trim();
          }
          if (
            item &&
            typeof item === 'object' &&
            'url' in item &&
            typeof item.url === 'string'
          ) {
            return item.url.trim();
          }
          return '';
        })
        .filter(Boolean);
    }

    if (
      value &&
      typeof value === 'object' &&
      'urls' in value &&
      Array.isArray(value.urls)
    ) {
      return value.urls
        .filter((item): item is string => typeof item === 'string')
        .map((item) => item.trim())
        .filter(Boolean);
    }

    return [];
  };

  try {
    return parseJsonValue(JSON.parse(trimmed));
  } catch {
    return /^https?:\/\//i.test(trimmed) ? [trimmed] : [];
  }
};

const mapContentDetail = (data: RawContentDetailResponseDTO): ContentDetailViewModel => ({
  postId: String(data.postId),
  authorId: String(data.authorId),
  authorName: data.authorNickname || '未知作者',
  authorAvatar: data.authorAvatarUrl || '',
  title: data.title || '未命名内容',
  content: data.content || '',
  summary: data.summary || '',
  mediaType: Number(data.mediaType ?? 0),
  mediaUrls: parseMediaInfo(data.mediaInfo),
  locationInfo: data.locationInfo || '',
  status: Number(data.status ?? 0),
  visibility: Number(data.visibility ?? 0),
  versionNum: Number(data.versionNum ?? 0),
  edited: Boolean(data.edited),
  createTime: Number(data.createTime ?? 0),
  likeCount: Number(data.likeCount ?? 0)
});

const mapDraftSyncResponse = (data: RawDraftSyncResponseDTO): DraftSyncResponseDTO => ({
  serverVersion: data.serverVersion,
  syncTime: Number(data.syncTime ?? 0)
});

const mapPublishAttempt = (
  data: RawPublishAttemptResponseDTO
): PublishAttemptResponseDTO => ({
  attemptId: String(data.attemptId),
  postId: String(data.postId),
  userId: String(data.userId),
  idempotentToken: data.idempotentToken || '',
  transcodeJobId: data.transcodeJobId || '',
  attemptStatus: Number(data.attemptStatus ?? 0),
  riskStatus: Number(data.riskStatus ?? 0),
  transcodeStatus: Number(data.transcodeStatus ?? 0),
  publishedVersionNum: Number(data.publishedVersionNum ?? 0),
  errorCode: data.errorCode || '',
  errorMessage: data.errorMessage || '',
  createTime: Number(data.createTime ?? 0),
  updateTime: Number(data.updateTime ?? 0)
});

const mapScheduleContent = (
  data: RawScheduleContentResponseDTO
): ScheduleContentResponseDTO => ({
  taskId: String(data.taskId),
  postId: String(data.postId),
  status: data.status
});

const mapScheduleAudit = (data: RawScheduleAuditResponseDTO): ScheduleAuditResponseDTO => ({
  taskId: String(data.taskId),
  userId: String(data.userId),
  scheduleTime: Number(data.scheduleTime ?? 0),
  status: Number(data.status ?? 0),
  retryCount: Number(data.retryCount ?? 0),
  isCanceled: Number(data.isCanceled ?? 0),
  lastError: data.lastError || '',
  alarmSent: Number(data.alarmSent ?? 0),
  contentData: data.contentData || ''
});

const mapHistoryVersion = (data: RawContentHistoryVersionDTO): ContentHistoryVersionDTO => ({
  versionId: String(data.versionId),
  title: data.title || '',
  content: data.content || '',
  time: Number(data.time ?? 0)
});

const toNullableCursor = (
  value?: string | number | null
): string | null => {
  if (value === null || value === undefined) {
    return null;
  }
  const nextValue = String(value).trim();
  return nextValue.length > 0 ? nextValue : null;
};

export const publishContent = (
  data: PublishContentRequestDTO
): Promise<PublishContentResponseDTO> => {
  return http.post<PublishContentResponseDTO>('/content/publish', data);
};

export const saveDraft = (data: SaveDraftRequestDTO): Promise<SaveDraftResponseDTO> => {
  return http.put<SaveDraftResponseDTO>('/content/draft', data);
};

export const syncDraft = async (
  draftId: string,
  data: DraftSyncRequestDTO
): Promise<DraftSyncResponseDTO> => {
  const response = await http.patch<RawDraftSyncResponseDTO>(`/content/draft/${draftId}`, data);
  return mapDraftSyncResponse(response);
};

export const createUploadSession = (
  data: UploadSessionRequestDTO
): Promise<UploadSessionResponseDTO> => {
  return http.post<UploadSessionResponseDTO>('/media/upload/session', data);
};

export const fetchContentDetail = async (
  postId: string,
  userId?: string
): Promise<ContentDetailViewModel> => {
  const response = await http.get<RawContentDetailResponseDTO>(`/content/${postId}`, {
    params: userId ? { userId } : undefined
  });
  return mapContentDetail(response);
};

export const fetchPublishAttempt = async (
  attemptId: string,
  userId?: string
): Promise<PublishAttemptResponseDTO> => {
  const response = await http.get<RawPublishAttemptResponseDTO>(
    `/content/publish/attempt/${attemptId}`,
    {
      params: userId ? { userId } : undefined
    }
  );
  return mapPublishAttempt(response);
};

export const scheduleContent = async (
  data: ScheduleContentRequestDTO
): Promise<ScheduleContentResponseDTO> => {
  const response = await http.post<RawScheduleContentResponseDTO>('/content/schedule', data);
  return mapScheduleContent(response);
};

export const updateSchedule = (
  data: ScheduleUpdateRequestDTO
): Promise<OperationResultDTO> => {
  return http.patch<OperationResultDTO>('/content/schedule', data);
};

export const cancelSchedule = (
  data: ScheduleCancelRequestDTO
): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/content/schedule/cancel', data);
};

export const fetchScheduleAudit = async (
  taskId: string,
  userId?: string
): Promise<ScheduleAuditResponseDTO> => {
  const response = await http.get<RawScheduleAuditResponseDTO>(`/content/schedule/${taskId}`, {
    params: userId ? { userId } : undefined
  });
  return mapScheduleAudit(response);
};

export const fetchContentHistory = async (
  postId: string,
  params: ContentHistoryRequestDTO = {}
): Promise<CursorPageResult<ContentHistoryVersionDTO>> => {
  const response = await http.get<RawContentHistoryResponseDTO>(`/content/${postId}/history`, {
    params
  });
  const nextCursor = toNullableCursor(response.nextCursor);

  return {
    items: (response.versions ?? []).map(mapHistoryVersion),
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};

export const rollbackContent = (
  postId: string,
  data: ContentRollbackRequestDTO
): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>(`/content/${postId}/rollback`, data);
};

export const deleteContent = (
  postId: string,
  data?: DeleteContentRequestDTO
): Promise<OperationResultDTO> => {
  return http.delete<OperationResultDTO>(`/content/${postId}`, { data });
};
