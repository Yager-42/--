import http from '@/utils/http';
import type { ApiResponse, OperationResultDTO } from './types';

export interface ReactionRequestDTO {
  requestId: string;
  targetId: string;
  targetType: string; // 'POST' or 'COMMENT'
  type: string; // 'LIKE', 'FAVORITE', etc.
  action: string; // 'ADD' or 'REMOVE'
}

export interface ReactionResponseDTO {
  requestId: string;
  currentCount: number;
  success: boolean;
}

export interface CommentRequestDTO {
  postId: string;
  parentId?: string;
  content: string;
}

export const postReaction = (data: ReactionRequestDTO) => {
  return http.post<ApiResponse<ReactionResponseDTO>>('/interact/reaction', data);
}

export const fetchComments = (postId: string) => {
  return http.get<ApiResponse<any>>('/comment/list', { params: { postId } });
}

export const postComment = (data: CommentRequestDTO) => {
  return http.post<ApiResponse<any>>('/interact/comment', data);
}
