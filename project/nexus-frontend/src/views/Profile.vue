<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchMyProfile, fetchUserProfile, type UserDTO } from '@/api/user'
import { fetchUserRiskStatus } from '@/api/risk'
import { useAuthStore } from '@/store/auth'
import FollowButton from '@/components/FollowButton.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const user = ref<UserDTO | null>(null)
const loading = ref(true)
const riskStatus = ref<string>('NORMAL')
const isMyProfile = computed(() => !route.params.userId || route.params.userId === authStore.userInfo?.userId)

const loadProfile = async () => {
  loading.value = true
  try {
    const userId = route.params.userId as string
    const res: any = userId ? await fetchUserProfile(userId) : await fetchMyProfile()
    user.value = res
    
    if (isMyProfile.value) {
      try {
        const risk: any = await fetchUserRiskStatus()
        riskStatus.value = risk.status
      } catch (e) {
        console.warn('Risk status check failed')
      }
    }
  } catch (err) {
    console.error('Failed to load profile', err)
  } finally {
    loading.value = false
  }
}

onMounted(loadProfile)
watch(() => route.params.userId, loadProfile)
</script>

<template>
  <div class="profile-view">
    <TheNavBar />
    
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
    </div>
    
    <div v-else-if="user" class="profile-content">
      <div v-if="riskStatus !== 'NORMAL'" class="risk-banner" @click="router.push('/settings/risk')">
        <span class="warning-icon">⚠️</span>
        <span class="risk-text">账号安全存在异常，点击查看详情并申诉</span>
        <span class="arrow">›</span>
      </div>

      <div class="header-section">
        <div class="avatar-wrapper">
          <img :src="user.avatar || 'https://via.placeholder.com/160'" class="profile-avatar" />
        </div>
        <h1 class="text-large-title">{{ user.nickname }}</h1>
        <p class="text-body text-secondary bio">{{ user.bio || '还没有填写个人签名' }}</p>
        
        <div class="action-bar">
          <FollowButton v-if="!isMyProfile" :user-id="user.userId" />
          <button v-else class="apple-btn-secondary">编辑资料</button>
        </div>
      </div>

      <div class="stats-grid">
        <div class="stat-tile">
          <span class="stat-value">{{ user.stats.likeCount }}</span>
          <span class="stat-label text-secondary">获赞</span>
        </div>
        <div class="stat-tile" @click="router.push(`/relation/following/${user.userId}`)">
          <span class="stat-value">{{ user.stats.followCount }}</span>
          <span class="stat-label text-secondary">关注</span>
        </div>
        <div class="stat-tile" @click="router.push(`/relation/followers/${user.userId}`)">
          <span class="stat-value">{{ user.stats.followerCount }}</span>
          <span class="stat-label text-secondary">粉丝</span>
        </div>
      </div>

      <div class="content-tabs">
        <div class="tab-item active">帖子</div>
        <div class="tab-item">收藏</div>
        <div class="tab-item">赞过</div>
      </div>
      
      <div class="empty-feed">
        <p class="text-secondary">暂无内容</p>
      </div>
    </div>
    
    <TheDock />
  </div>
</template>

<style scoped>
.profile-view {
  height: 100vh;
  padding-top: 44px;
  background-color: var(--apple-bg);
  overflow-y: auto;
}

.loading-state {
  height: calc(100vh - 44px);
  display: flex;
  justify-content: center;
  align-items: center;
}

.profile-content {
  padding: 32px 16px 120px;
}

.risk-banner {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.warning-icon {
  font-size: 18px;
}

.risk-text {
  flex: 1;
  font-size: 14px;
  color: #856404;
  font-weight: 500;
}

.arrow {
  font-size: 20px;
  color: #856404;
  opacity: 0.5;
}

.header-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-bottom: 40px;
}

.avatar-wrapper {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  overflow: hidden;
  margin-bottom: 20px;
  border: 1px solid rgba(0,0,0,0.05);
}

.profile-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bio {
  margin-top: 8px;
  max-width: 80%;
}

.action-bar {
  margin-top: 24px;
  width: 100%;
  display: flex;
  justify-content: center;
}

.apple-btn-secondary {
  background: #f5f5f7;
  border: none;
  padding: 8px 32px;
  border-radius: 20px;
  font-size: 15px;
  font-weight: 600;
  color: var(--apple-text);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 40px;
}

.stat-tile {
  background: #f5f5f7;
  border-radius: 16px;
  padding: 16px 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  transition: transform 0.1s var(--spring-easing);
}

.stat-tile:active {
  transform: scale(0.95);
}

.stat-value {
  font-size: 19px;
  font-weight: 700;
  margin-bottom: 2px;
}

.stat-label {
  font-size: 13px;
  font-weight: 500;
}

.content-tabs {
  display: flex;
  justify-content: center;
  gap: 40px;
  border-bottom: 0.5px solid #eee;
  padding-bottom: 12px;
  margin-bottom: 24px;
}

.tab-item {
  font-size: 15px;
  font-weight: 600;
  color: var(--apple-text-secondary);
  position: relative;
}

.tab-item.active {
  color: var(--apple-text);
}

.tab-item.active::after {
  content: "";
  position: absolute;
  bottom: -12px;
  left: 50%;
  transform: translateX(-50%);
  width: 20px;
  height: 2px;
  background: var(--apple-text);
}

.empty-feed {
  padding: 80px 0;
  text-align: center;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid rgba(0,0,0,0.1);
  border-top-color: var(--apple-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
