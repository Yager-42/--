<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProfileTimeline, type FeedCardViewModel } from '@/api/feed'
import { blockUser } from '@/api/relation'
import {
  fetchMyPrivacy,
  fetchProfilePage,
  updateMyPrivacy,
  updateMyProfile,
  type ProfilePageViewModel
} from '@/api/user'
import FollowButton from '@/components/FollowButton.vue'
import EditProfilePanel from '@/components/profile/EditProfilePanel.vue'
import ProfileActionMenu from '@/components/profile/ProfileActionMenu.vue'
import ProfileFeedGrid from '@/components/profile/ProfileFeedGrid.vue'
import ProfilePrivacyPanel from '@/components/profile/ProfilePrivacyPanel.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import { useAuthStore } from '@/store/auth'

interface CursorPageState {
  nextCursor: string | null
  hasMore: boolean
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const user = ref<ProfilePageViewModel | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const saveMessage = ref('')
const editing = ref(false)
const draftNickname = ref('')
const draftAvatarUrl = ref('')
const profileFeed = ref<FeedCardViewModel[]>([])
const profileFeedPage = ref<CursorPageState>({ nextCursor: null, hasMore: false })
const profileFeedLoading = ref(false)
const profileFeedLoadingMore = ref(false)
const profileFeedError = ref('')
const privacyNeedApproval = ref<boolean | null>(null)
const privacyLoaded = ref(false)
const privacyError = ref('')
const privacyLoading = ref(false)
const privacySaving = ref(false)
const blocking = ref(false)

const routeUserId = computed(() =>
  typeof route.params.userId === 'string' ? route.params.userId : null
)
const isMyProfile = computed(() => !routeUserId.value || routeUserId.value === authStore.userId)
const profileDescription = computed(() =>
  user.value?.bio
  || 'Visual storyteller and curator exploring the intersection of light, shadow, and silence through a calmer Nexus profile shell.'
)

const createEmptyPage = (): CursorPageState => ({
  nextCursor: null,
  hasMore: false
})

const syncDraft = (profile: ProfilePageViewModel | null) => {
  draftNickname.value = profile?.nickname || ''
  draftAvatarUrl.value = profile?.avatar || ''
}

const resetProfileFeedState = () => {
  profileFeed.value = []
  profileFeedPage.value = createEmptyPage()
  profileFeedError.value = ''
}

const resetPrivacyState = () => {
  privacyNeedApproval.value = null
  privacyLoaded.value = false
  privacyError.value = ''
}

const loadProfile = async (): Promise<ProfilePageViewModel | null> => {
  const targetUserId = routeUserId.value ?? authStore.userId
  if (!targetUserId) {
    error.value = '缺少用户标识，请重新登录'
    user.value = null
    return null
  }

  loading.value = true
  error.value = ''
  try {
    const response = await fetchProfilePage(targetUserId)
    user.value = response
    syncDraft(response)

    if (!routeUserId.value) {
      authStore.setUserId(response.userId)
    }

    return response
  } catch (e) {
    error.value = e instanceof Error ? e.message : '个人信息加载失败'
    user.value = null
    return null
  } finally {
    loading.value = false
  }
}

const loadProfileFeed = async (targetUserId: string, append = false) => {
  if (append) {
    if (!profileFeedPage.value.hasMore || !profileFeedPage.value.nextCursor) {
      return
    }
    profileFeedLoadingMore.value = true
  } else {
    profileFeedLoading.value = true
    profileFeedError.value = ''
  }

  try {
    const response = await fetchProfileTimeline({
      targetId: targetUserId,
      cursor: append ? profileFeedPage.value.nextCursor ?? undefined : undefined,
      limit: 6
    })

    profileFeed.value = append
      ? [...profileFeed.value, ...response.items]
      : response.items
    profileFeedPage.value = response.page
  } catch (e) {
    profileFeedError.value = append
      ? '加载更多失败，请稍后重试'
      : e instanceof Error
        ? e.message
        : '作者内容加载失败'
    if (!append) {
      profileFeed.value = []
      profileFeedPage.value = createEmptyPage()
    }
  } finally {
    if (append) {
      profileFeedLoadingMore.value = false
    } else {
      profileFeedLoading.value = false
    }
  }
}

const loadPrivacy = async () => {
  if (!isMyProfile.value) {
    resetPrivacyState()
    return
  }

  privacyLoading.value = true
  privacyLoaded.value = false
  privacyError.value = ''
  try {
    const response = await fetchMyPrivacy()
    privacyNeedApproval.value = response.needApproval
    privacyLoaded.value = true
  } catch (e) {
    privacyNeedApproval.value = null
    privacyError.value = e instanceof Error && e.message
      ? '隐私设置暂时不可用'
      : '隐私设置暂时不可用'
  } finally {
    privacyLoading.value = false
  }
}

const loadProfileSurface = async () => {
  resetProfileFeedState()
  resetPrivacyState()
  const profile = await loadProfile()
  if (!profile) {
    return
  }

  await Promise.all([
    loadProfileFeed(profile.userId),
    loadPrivacy()
  ])
}

const saveProfile = async () => {
  if (!isMyProfile.value) return

  saving.value = true
  error.value = ''
  saveMessage.value = ''
  try {
    await updateMyProfile({
      nickname: draftNickname.value.trim(),
      avatarUrl: draftAvatarUrl.value.trim()
    })
    saveMessage.value = '资料已更新。'
    editing.value = false
    await loadProfile()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '资料保存失败'
  } finally {
    saving.value = false
  }
}

const togglePrivacy = async (nextNeedApproval: boolean) => {
  if (!isMyProfile.value || !privacyLoaded.value || privacyNeedApproval.value === null) return

  privacySaving.value = true
  error.value = ''
  saveMessage.value = ''
  try {
    await updateMyPrivacy({ needApproval: nextNeedApproval })
    privacyNeedApproval.value = nextNeedApproval
    privacyError.value = ''
    privacyLoaded.value = true
    saveMessage.value = '隐私设置已更新。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '隐私设置保存失败'
  } finally {
    privacySaving.value = false
  }
}

const blockProfileUser = async () => {
  if (isMyProfile.value || !routeUserId.value) return

  const viewerId = authStore.userId
  if (!viewerId) {
    error.value = '缺少当前登录用户，无法执行屏蔽'
    return
  }

  blocking.value = true
  error.value = ''
  try {
    await blockUser({
      sourceId: viewerId,
      targetId: routeUserId.value
    })
    await router.push('/search')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '屏蔽失败，请稍后重试'
  } finally {
    blocking.value = false
  }
}

const cancelEdit = () => {
  syncDraft(user.value)
  editing.value = false
}

const loadMoreProfileFeed = async () => {
  if (!user.value) {
    return
  }

  await loadProfileFeed(user.value.userId, true)
}

onMounted(() => {
  void loadProfileSurface()
})

watch(
  () => route.params.userId,
  () => {
    editing.value = false
    saveMessage.value = ''
    void loadProfileSurface()
  }
)
</script>

<template>
  <PrototypeShell>
    <article data-prototype-profile class="space-y-16 pb-24">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="space-y-3">
          <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
            Profile
          </p>
          <h1 class="max-w-[10ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
            A calmer creator profile.
          </h1>
          <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
            The page now keeps the large identity block, but the archive is backfilled with real
            posts and owner or visitor controls instead of prototype-only gallery chips.
          </p>
        </div>
      </PrototypeContainer>

      <PrototypeContainer v-if="loading" width="content">
        <StatePanel
          variant="loading"
          title="正在准备资料页"
          body="个人资料与关系状态正在同步。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="error && !user" width="content">
        <StatePanel
          variant="request-failure"
          :body="error"
          action-label="重试"
          @action="loadProfileSurface"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="user" class="space-y-10">
        <button
          v-if="user.riskStatus !== 'NORMAL'"
          type="button"
          class="inline-flex min-h-[3rem] items-center gap-3 rounded-full border border-error/20 bg-[rgba(158,66,44,0.08)] px-5 text-sm font-semibold text-error transition hover:border-error/35"
          @click="router.push('/settings/risk')"
        >
          <ZenIcon name="warning" :size="18" />
          账号当前存在风险状态，查看限制与申诉路径
        </button>

        <section class="grid gap-8 lg:grid-cols-[12rem,minmax(0,1fr)] lg:items-center">
          <div class="relative">
            <div class="h-44 w-44 overflow-hidden rounded-full bg-prototype-surface p-1.5 shadow-[0_18px_44px_rgba(27,31,31,0.1)]">
              <img
                :src="user.avatar || 'https://via.placeholder.com/320'"
                :alt="user.nickname"
                class="h-full w-full rounded-full object-cover"
              >
            </div>
            <div class="absolute bottom-3 right-3 flex h-11 w-11 items-center justify-center rounded-full bg-prototype-ink text-prototype-surface shadow-[0_12px_28px_rgba(27,31,31,0.2)]">
              <ZenIcon name="verified" :size="18" />
            </div>
          </div>

          <div class="space-y-5">
            <div class="flex flex-wrap items-center gap-4">
              <h2 class="font-headline text-5xl tracking-[-0.05em] text-prototype-ink">
                {{ user.nickname }}
              </h2>
              <div class="flex items-center gap-3">
                <FollowButton
                  v-if="!isMyProfile"
                  :user-id="user.userId"
                  :relation-state="user.relationState"
                />
                <ZenButton v-if="isMyProfile" variant="primary" @click="editing = true">
                  编辑资料
                </ZenButton>
                <ZenButton
                  v-if="isMyProfile"
                  variant="secondary"
                  @click="router.push(`/relation/following/${user.userId}`)"
                >
                  查看关注
                </ZenButton>
              </div>
            </div>

            <p class="max-w-3xl text-lg leading-8 text-prototype-muted">
              {{ profileDescription }}
            </p>

            <div class="flex flex-wrap gap-8 pt-2">
              <div class="grid gap-1">
                <span class="text-2xl font-bold text-prototype-ink">{{ user.stats.likeCount }}</span>
                <span class="text-xs uppercase tracking-[0.18em] text-prototype-muted">Exhibits</span>
              </div>
              <div class="grid gap-1">
                <span class="text-2xl font-bold text-prototype-ink">{{ user.stats.followerCount }}</span>
                <span class="text-xs uppercase tracking-[0.18em] text-prototype-muted">Curators</span>
              </div>
              <div class="grid gap-1">
                <span class="text-2xl font-bold text-prototype-ink">{{ user.stats.followCount }}</span>
                <span class="text-xs uppercase tracking-[0.18em] text-prototype-muted">Following</span>
              </div>
            </div>
          </div>
        </section>

        <FormMessage v-if="error && user" tone="error" :message="error" />
        <FormMessage v-if="saveMessage" tone="success" :message="saveMessage" />

        <EditProfilePanel
          v-if="isMyProfile && editing"
          :nickname="draftNickname"
          :avatar-url="draftAvatarUrl"
          :loading="saving"
          @update:nickname="draftNickname = $event"
          @update:avatar-url="draftAvatarUrl = $event"
          @cancel="cancelEdit"
          @save="saveProfile"
        />

        <section class="grid gap-10 xl:grid-cols-[minmax(0,1fr),20rem] xl:items-start">
          <ProfileFeedGrid
            :items="profileFeed"
            :loading="profileFeedLoading"
            :error="profileFeedError"
            :has-more="profileFeedPage.hasMore"
            :loading-more="profileFeedLoadingMore"
            @retry="loadProfileFeed(user.userId)"
            @load-more="loadMoreProfileFeed"
          />

          <div class="space-y-6">
            <ProfilePrivacyPanel
              v-if="isMyProfile"
              :need-approval="privacyNeedApproval"
              :loaded="privacyLoaded"
              :error="privacyError"
              :loading="privacyLoading"
              :saving="privacySaving"
              @toggle="togglePrivacy"
            />

            <ProfileActionMenu
              v-else
              :blocking="blocking"
              @block="blockProfileUser"
            />
          </div>
        </section>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
