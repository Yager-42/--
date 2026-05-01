import type { ProfileHeaderViewModel, UserProfilePageResponseDTO, UserProfileResponseDTO } from '@/types/profile'

export function mapProfileHeader(
  profile: UserProfileResponseDTO,
  relation?: UserProfilePageResponseDTO['relation']
): ProfileHeaderViewModel {
  return {
    id: String(profile.userId),
    nickname: profile.nickname || profile.username,
    bio: profile.status || '这个用户还没有留下简介。',
    avatarUrl: profile.avatarUrl,
    followerCountLabel: String(relation?.followerCount ?? 0),
    followingCountLabel: String(relation?.followCount ?? 0),
    isFollowing: relation?.isFollow ?? false
  }
}
