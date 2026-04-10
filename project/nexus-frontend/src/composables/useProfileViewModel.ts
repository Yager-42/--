import { computed, ref } from 'vue'
import type { ProfilePageViewModel } from '@/api/user'

export const useProfileViewModel = () => {
  const draftNickname = ref('')
  const draftAvatarUrl = ref('')

  const syncDraft = (profile: ProfilePageViewModel | null) => {
    draftNickname.value = profile?.nickname || ''
    draftAvatarUrl.value = profile?.avatar || ''
  }

  const profileCards = (profile: ProfilePageViewModel | null) => {
    if (!profile) return []

    return [
      {
        id: `${profile.userId}-liked`,
        title: '获赞统计',
        subtitle: `${profile.stats.likeCount} 次认可`
      },
      {
        id: `${profile.userId}-following`,
        title: '关注中的人',
        subtitle: `${profile.stats.followCount} 位创作者`
      },
      {
        id: `${profile.userId}-followers`,
        title: '关注你的用户',
        subtitle: `${profile.stats.followerCount} 位关注者`
      }
    ]
  }

  const hasDraftChanges = computed(
    () => draftNickname.value.trim().length > 0 || draftAvatarUrl.value.trim().length > 0
  )

  return {
    draftNickname,
    draftAvatarUrl,
    syncDraft,
    profileCards,
    hasDraftChanges
  }
}

