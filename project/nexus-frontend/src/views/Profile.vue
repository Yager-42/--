<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProfilePage, type ProfilePageViewModel } from '@/api/user'
import { useAuthStore } from '@/store/auth'
import FollowButton from '@/components/FollowButton.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const user = ref<ProfilePageViewModel | null>(null)
const loading = ref(false)
const error = ref('')

const routeUserId = computed(() =>
  typeof route.params.userId === 'string' ? route.params.userId : null
)
const isMyProfile = computed(() => !routeUserId.value || routeUserId.value === authStore.userId)

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

onMounted(() => {
  void loadProfile()
})

watch(() => route.params.userId, () => {
  void loadProfile()
})
</script>

<template>
  <div class="page-shell with-full-nav">
    <TheNavBar />

    <main class="page-content profile-page">
      <section v-if="loading" class="state-card">
        <div class="spinner"></div>
        正在加载资料...
      </section>

      <section v-else-if="error" class="state-card error">
        {{ error }}
      </section>

      <section v-else-if="user" class="profile-card surface-card">
        <button
          v-if="user.riskStatus !== 'NORMAL'"
          class="risk-banner"
          type="button"
          @click="router.push('/settings/risk')"
        >
          账号安全存在风险，点击查看申诉中心
        </button>

        <div class="hero">
          <img :src="user.avatar || 'https://via.placeholder.com/160'" alt="avatar" class="avatar">
          <h1 class="text-large-title">{{ user.nickname }}</h1>
          <p class="text-secondary">{{ user.bio || '这个用户还没有填写个人简介。' }}</p>

          <FollowButton
            v-if="!isMyProfile"
            :user-id="user.userId"
            :relation-state="user.relationState"
          />
          <button v-else class="secondary-btn edit-btn" type="button">编辑资料（开发中）</button>
        </div>

        <div class="stats">
          <button type="button" class="stat-item">
            <strong>{{ user.stats.likeCount }}</strong>
            <span>获赞</span>
          </button>
          <button
            type="button"
            class="stat-item"
            @click="router.push(`/relation/following/${user.userId}`)"
          >
            <strong>{{ user.stats.followCount }}</strong>
            <span>关注</span>
          </button>
          <button
            type="button"
            class="stat-item"
            @click="router.push(`/relation/followers/${user.userId}`)"
          >
            <strong>{{ user.stats.followerCount }}</strong>
            <span>粉丝</span>
          </button>
        </div>
      </section>
    </main>

    <TheDock />
  </div>
</template>

<style scoped>
.profile-page {
  display: grid;
}

.profile-card {
  padding: 18px;
  display: grid;
  gap: 16px;
}

.risk-banner {
  min-height: 44px;
  border-radius: 12px;
  border: 1px solid #facc15;
  background: #fffbeb;
  color: #854d0e;
  font-weight: 600;
  padding: 0 14px;
  text-align: left;
}

.hero {
  display: grid;
  justify-items: center;
  text-align: center;
  gap: 8px;
}

.avatar {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  object-fit: cover;
}

.edit-btn {
  min-width: 132px;
  padding: 0 14px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.stat-item {
  min-height: 72px;
  border-radius: 14px;
  border: 1px solid var(--border-soft);
  background: var(--bg-elevated);
  display: grid;
  place-items: center;
  color: var(--text-primary);
}

.stat-item strong {
  font-size: 1.15rem;
}

.stat-item span {
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.state-card {
  min-height: 120px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-secondary);
}

.error {
  color: var(--brand-danger);
}
</style>


