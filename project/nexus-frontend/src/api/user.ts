import http from '@/utils/http';
import type { OperationResultDTO, RelationState } from './types';

export interface UserDTO {
  userId: string;
  username?: string;
  nickname: string;
  avatar: string;
  bio: string;
  status: string;
  stats: {
    likeCount: number;
    followCount: number;
    followerCount: number;
  };
}

interface RawUserProfileResponseDTO {
  userId: number | string;
  username: string;
  nickname: string;
  avatarUrl: string;
  status: string;
}

interface RawUserRelationStatsDTO {
  followCount: number;
  followerCount: number;
  isFollow: boolean;
}

interface RawUserRiskStatusResponseDTO {
  status: string;
  capabilities: string[];
}

interface RawUserProfilePageResponseDTO {
  profile: RawUserProfileResponseDTO;
  relation: RawUserRelationStatsDTO;
  risk: RawUserRiskStatusResponseDTO;
}

export interface UserProfileUpdateRequestDTO {
  nickname?: string;
  avatarUrl?: string;
}

export interface UserPrivacyResponseDTO {
  needApproval: boolean;
}

export interface UserPrivacyUpdateRequestDTO {
  needApproval: boolean;
}

export interface ProfilePageViewModel extends UserDTO {
  riskStatus: string;
  relationState: RelationState;
}

const mapUserProfile = (data: RawUserProfileResponseDTO): UserDTO => ({
  userId: String(data.userId),
  username: data.username,
  nickname: data.nickname,
  avatar: data.avatarUrl || '',
  bio: '',
  status: data.status,
  stats: {
    likeCount: 0,
    followCount: 0,
    followerCount: 0
  }
});

const mapProfilePage = (data: RawUserProfilePageResponseDTO): ProfilePageViewModel => ({
  userId: String(data.profile.userId),
  username: data.profile.username,
  nickname: data.profile.nickname,
  avatar: data.profile.avatarUrl || '',
  bio: '',
  status: data.profile.status,
  stats: {
    likeCount: 0,
    followCount: Number(data.relation.followCount ?? 0),
    followerCount: Number(data.relation.followerCount ?? 0)
  },
  riskStatus: data.risk.status,
  relationState: data.relation.isFollow ? 'FOLLOWING' : 'NOT_FOLLOWING'
});

export const fetchMyProfile = async (): Promise<UserDTO> => {
  const response = await http.get<RawUserProfileResponseDTO>('/user/me/profile');
  return mapUserProfile(response);
};

export const fetchUserProfile = async (userId: string): Promise<UserDTO> => {
  const response = await http.get<RawUserProfileResponseDTO>('/user/profile', {
    params: { targetUserId: userId }
  });
  return mapUserProfile(response);
};

export const fetchProfilePage = async (targetUserId: string): Promise<ProfilePageViewModel> => {
  const response = await http.get<RawUserProfilePageResponseDTO>('/user/profile/page', {
    params: { targetUserId }
  });
  return mapProfilePage(response);
};

export const updateMyProfile = (
  data: UserProfileUpdateRequestDTO
): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/user/me/profile', data);
};

export const fetchMyPrivacy = (): Promise<UserPrivacyResponseDTO> => {
  return http.get<UserPrivacyResponseDTO>('/user/me/privacy');
};

export const updateMyPrivacy = (
  data: UserPrivacyUpdateRequestDTO
): Promise<OperationResultDTO> => {
  return http.post<OperationResultDTO>('/user/me/privacy', data);
};
