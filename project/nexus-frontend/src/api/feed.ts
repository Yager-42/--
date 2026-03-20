import http from '@/utils/http';
import type { ApiResponse } from './types';

export interface FeedItemDTO {
  postId: string;
  authorId: string;
  authorName: string;
  authorAvatar: string;
  contentTitle: string;
  contentBody: string;
  mediaUrls: string[];
  reactionCount: number;
  commentCount: number;
  createTime: number;
  isLiked?: boolean;
}

export interface FeedTimelineResponseDTO {
  items: FeedItemDTO[];
  nextCursor: string;
}

export interface FeedTimelineRequestDTO {
  cursor?: string;
  limit?: number;
  feedType?: string;
}

export const fetchTimeline = (params: FeedTimelineRequestDTO) => {
  return http.get<ApiResponse<FeedTimelineResponseDTO>>('/feed/timeline', { params });
}
