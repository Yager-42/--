export type UserProfileResponseDTO = {
  userId: number
  username: string
  nickname: string
  avatarUrl?: string
  status: string
}

export type UserRelationStatsDTO = {
  followCount: number
  followerCount: number
  isFollow: boolean
}

export type UserProfilePageResponseDTO = {
  profile: UserProfileResponseDTO
  relation: UserRelationStatsDTO
}

export type UserPrivacyResponseDTO = {
  needApproval: boolean
}

export type UserProfileUpdatePayload = {
  nickname: string
  avatarUrl?: string
}

export type UserPrivacyUpdatePayload = {
  needApproval: boolean
}

export type FollowPayload = {
  sourceId: number
  targetId: number
}

export type FollowResponseDTO = {
  status: string
}

export type RelationUserDTO = {
  userId: number
  nickname: string
  avatar?: string
  followTime: number
}

export type RelationListResponseDTO = {
  items: RelationUserDTO[]
  nextCursor?: string
}

export type RelationStateBatchPayload = {
  targetUserIds: number[]
}

export type RelationStateBatchResponseDTO = {
  followingUserIds: number[]
  blockedUserIds: number[]
}

export type RelationUserViewModel = {
  id: string
  nickname: string
  avatarUrl?: string
  followTimeLabel: string
  isFollowing?: boolean
}

export type ProfileHeaderViewModel = {
  id: string
  nickname: string
  bio: string
  avatarUrl?: string
  followerCountLabel: string
  followingCountLabel: string
  isFollowing: boolean
}

export type MyProfileViewModel = ProfileHeaderViewModel & {
  needApproval: boolean
}
