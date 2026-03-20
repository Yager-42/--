import http from '@/utils/http';
import type { ApiResponse } from './types';

export interface FollowRequestDTO {
  sourceId: string;
  targetId: string;
}

export interface RelationUserDTO {
  userId: string;
  nickname: string;
  avatar: string;
  bio: string;
  isFollowing: boolean;
}

export interface RelationListResponseDTO {
  items: RelationUserDTO[];
  nextCursor: string;
}

export const followUser = (targetId: string) => {
  return http.post<ApiResponse<any>>('/relation/follow', { targetId });
}

export const unfollowUser = (targetId: string) => {
  return http.post<ApiResponse<any>>('/relation/unfollow', { targetId });
}

export const fetchFollowers = (params: { userId: string, cursor?: string }) => {
  return http.get<ApiResponse<RelationListResponseDTO>>('/relation/followers', { params });
}

export const fetchFollowing = (params: { userId: string, cursor?: string }) => {
  return http.get<ApiResponse<RelationListResponseDTO>>('/relation/following', { params });
}

export const checkRelationState = (targetUserIds: string[]) => {
  return http.post<ApiResponse<any>>('/relation/state/batch', { targetUserIds });
}
