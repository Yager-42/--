import http from '@/utils/http';
import type { ApiResponse } from './types';

export interface UserDTO {
  userId: string;
  nickname: string;
  avatar: string;
  bio: string;
  stats: {
    likeCount: number;
    followCount: number;
    followerCount: number;
  };
}

export interface UserProfileUpdateRequestDTO {
  nickname?: string;
  avatar?: string;
  bio?: string;
}

export const fetchMyProfile = () => {
  return http.get<ApiResponse<UserDTO>>('/user/me/profile');
}

export const fetchUserProfile = (userId: string) => {
  return http.get<ApiResponse<UserDTO>>('/user/profile', { params: { userId } });
}

export const updateMyProfile = (data: UserProfileUpdateRequestDTO) => {
  return http.post<ApiResponse<any>>('/user/me/profile', data);
}
