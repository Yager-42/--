<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProfilePage, updateMyProfile, type ProfilePageViewModel } from '@/api/user'
import { useAuthStore } from '@/store/auth'
import FollowButton from '@/components/FollowButton.vue'
import EditProfilePanel from '@/components/profile/EditProfilePanel.vue'
import ProfileContentGrid from '@/components/profile/ProfileContentGrid.vue'
import ProfileHero from '@/components/profile/ProfileHero.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import { useProfileViewModel } from '@/composables/useProfileViewModel'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const user = ref<ProfilePageViewModel | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const saveMessage = ref('')
const editing = ref(false)

const routeUserId = computed(() =>
  typeof route.params.userId === 'string' ? route.params.userId : null
)
const isMyProfile = computed(() => !routeUserId.value || routeUserId.value === authStore.userId)

const {
  draftNickname,
  draftAvatarUrl,
  syncDraft,
  profileCards
} = useProfileViewModel()

const cards = computed(() => profileCards(user.value))
const profileHeading = computed(() => {
  if (user.value) {
    return isMyProfile.value ? 'Your profile and archive.' : `${user.value.nickname}'s profile`
  }
  return isMyProfile.value ? 'Your profile and archive.' : 'Creator profile'
})
const profileDescription = computed(() =>
  isMyProfile.value
    ? '保留真实资料、关注关系与编辑流程，并让个人主页回到更克制的桌面阅读层级。'
    : '展示真实资料与关系状态，不额外引入自我编辑壳层或多余运营模块。'
)

const loadProfile = async () => {
  const targetUserId = routeUserId.value ?? authStore.userId
  if (!targetUserId) {
    error.value = '缺少用户标识，请重新登录'
    user.value = null
    return
  }

  loading.value = true
  error.value = ''
  try {
    const res = await fetchProfilePage(targetUserId)
    user.value = res
    syncDraft(res)

    if (!routeUserId.value) {
      authStore.setUserId(res.userId)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '个人信息加载失败'
    user.value = null
  } finally {
    loading.value = false
  }
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

const cancelEdit = () => {
  syncDraft(user.value)
  editing.value = false
}

onMounted(() => {
  void loadProfile()
})

watch(
  () => route.params.userId,
  () => {
    editing.value = false
    void loadProfile()
  }
)
</script>

<template>
  <PrototypeShell>
    <article data-prototype-profile class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="space-y-3">
          <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
            Profile
          </p>
          <h1 class="max-w-[10ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
            {{ profileHeading }}
          </h1>
          <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
            {{ profileDescription }}
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
          @action="loadProfile"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="user" width="content" class="space-y-6">
        <button
          v-if="user.riskStatus !== 'NORMAL'"
          class="inline-flex min-h-[3rem] items-center justify-center rounded-full border border-error/20 bg-[rgba(158,66,44,0.08)] px-5 text-sm font-semibold text-error transition hover:border-error/35"
          type="button"
          @click="router.push('/settings/risk')"
        >
          账号当前存在风险状态，查看限制与申诉路径
        </button>

        <section class="space-y-6 rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8">
          <ProfileHero :profile="user" :is-my-profile="isMyProfile" @edit="editing = true" />

          <div class="flex flex-wrap items-center gap-3">
            <FollowButton
              v-if="!isMyProfile"
              :user-id="user.userId"
              :relation-state="user.relationState"
            />

            <ZenButton
              v-if="isMyProfile"
              variant="secondary"
              @click="router.push(`/relation/following/${user.userId}`)"
            >
              查看关注
            </ZenButton>
          </div>

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

          <ProfileContentGrid :items="cards" />
        </section>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
