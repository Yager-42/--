import http from '@/utils/http';
import type { CursorPageResult, PostCardViewModel } from './types';

export interface RawFeedItemDTO {
  postId: number | string;
  authorId: number | string;
  authorNickname: string;
  authorAvatar: string;
  text: string;
  summary: string;
  mediaType?: number;
  mediaInfo?: string | null;
  publishTime: number;
  source?: string;
  likeCount: number;
  liked: boolean;
  followed?: boolean;
  seen?: boolean;
}

export interface FeedTimelineResponseDTO {
  items: RawFeedItemDTO[];
  nextCursor?: string | null;
}

export interface FeedTimelineRequestDTO {
  userId?: string;
  cursor?: string;
  limit?: number;
  feedType?: string;
}

export interface ProfileTimelineRequestDTO {
  targetId: string;
  userId?: string;
  cursor?: string;
  limit?: number;
}

export interface FeedCardViewModel extends PostCardViewModel {
  postId: string;
  authorId: string;
  authorAvatar: string;
  mediaUrls: string[];
  createTime: number;
}

const FALLBACK_POST_IMAGE = 'https://via.placeholder.com/800x1200';

const toNullableCursor = (value?: string | null): string | null => {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const truncateText = (value: string, maxLength = 32): string => {
  const trimmed = value.trim();
  if (trimmed.length <= maxLength) {
    return trimmed;
  }
  return `${trimmed.slice(0, maxLength)}...`;
};

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

const mapFeedItem = (item: RawFeedItemDTO): FeedCardViewModel => {
  const mediaUrls = parseMediaInfo(item.mediaInfo);
  const contentBody = item.text ?? '';
  const contentTitle = item.summary?.trim() || truncateText(contentBody) || '未命名内容';

  return {
    id: String(item.postId),
    postId: String(item.postId),
    authorId: String(item.authorId),
    author: item.authorNickname || '匿名用户',
    authorAvatar: item.authorAvatar || '',
    title: contentTitle,
    body: contentBody,
    image: mediaUrls[0] || FALLBACK_POST_IMAGE,
    mediaUrls,
    createTime: item.publishTime,
    reactionCount: Number(item.likeCount ?? 0),
    commentCount: 0,
    isLiked: Boolean(item.liked)
  };
};

export const fetchTimeline = async (
  params: FeedTimelineRequestDTO
): Promise<CursorPageResult<FeedCardViewModel>> => {
  const response = await http.get<FeedTimelineResponseDTO>('/feed/timeline', { params });
  const nextCursor = toNullableCursor(response.nextCursor);

  return {
    items: response.items.map(mapFeedItem),
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};

export const fetchProfileTimeline = async (
  params: ProfileTimelineRequestDTO
): Promise<CursorPageResult<FeedCardViewModel>> => {
  const { targetId, ...query } = params;
  const response = await http.get<FeedTimelineResponseDTO>(`/feed/profile/${targetId}`, {
    params: query
  });
  const nextCursor = toNullableCursor(response.nextCursor);

  return {
    items: response.items.map(mapFeedItem),
    page: {
      nextCursor,
      hasMore: nextCursor !== null
    }
  };
};
