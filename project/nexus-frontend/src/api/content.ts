import http from '@/utils/http';

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

export const publishContent = (
  data: PublishContentRequestDTO
): Promise<PublishContentResponseDTO> => {
  return http.post<PublishContentResponseDTO>('/content/publish', data);
};

export const saveDraft = (data: SaveDraftRequestDTO): Promise<SaveDraftResponseDTO> => {
  return http.put<SaveDraftResponseDTO>('/content/draft', data);
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
