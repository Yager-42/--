<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProfilePage, updateMyProfile, type ProfilePageViewModel } from '@/api/user'
import { useAuthStore } from '@/store/auth'
import FollowButton from '@/components/FollowButton.vue'
import EditProfilePanel from '@/components/profile/EditProfilePanel.vue'
import ProfileContentGrid from '@/components/profile/ProfileContentGrid.vue'
import ProfileHero from '@/components/profile/ProfileHero.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import TheDock from '@/components/TheDock.vue'
import TheNavBar from '@/components/TheNavBar.vue'
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
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main page-main--dock">
      <section class="grid gap-6">
        <StatePanel
          v-if="loading"
          variant="loading"
          title="正在准备资料页"
          body="个人资料与关系状态正在同步。"
        />

        <StatePanel
          v-else-if="error && !user"
          variant="request-failure"
          :body="error"
          action-label="重试"
          @action="loadProfile"
        />

        <template v-else-if="user">
          <button
            v-if="user.riskStatus !== 'NORMAL'"
            class="profile-risk"
            type="button"
            @click="router.push('/settings/risk')"
          >
            账号当前存在风险状态，查看限制与申诉路径
          </button>

          <section class="paper-panel grid gap-6 p-6 md:p-8">
            <ProfileHero :profile="user" :is-my-profile="isMyProfile" @edit="editing = true" />

            <div class="profile-actions">
              <FollowButton
                v-if="!isMyProfile"
                :user-id="user.userId"
                :relation-state="user.relationState"
              />

              <button
                v-if="isMyProfile"
                type="button"
                class="secondary-btn"
                @click="router.push(`/relation/following/${user.userId}`)"
              >
                查看关注
              </button>
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
        </template>
      </section>
    </main>

    <TheDock />
  </div>
</template>

<style scoped>
.profile-risk {
  min-height: 3rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 1rem;
  border-radius: 999px;
  background: rgba(148, 80, 64, 0.14);
  color: var(--brand-danger);
  border: 1px solid rgba(148, 80, 64, 0.22);
}

.profile-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
</style>
