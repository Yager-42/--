<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFollowers, fetchFollowing, type RelationUserDTO } from '@/api/relation'
import TheNavBar from '@/components/TheNavBar.vue'
import FollowButton from '@/components/FollowButton.vue'

const route = useRoute()
const router = useRouter()

const type = ref(route.params.type as string) // 'followers' or 'following'
const userId = ref(route.params.userId as string)
const items = ref<RelationUserDTO[]>([])
const loading = ref(true)

const loadData = async () => {
  loading.value = true
  try {
    const res: any = type.value === 'followers' 
      ? await fetchFollowers({ userId: userId.value })
      : await fetchFollowing({ userId: userId.value })
    items.value = res.items || []
  } catch (err) {
    console.error('Failed to load relation list', err)
  } finally {
    loading.value = false
  }
}

const toggleTab = (newType: string) => {
  if (type.value === newType) return
  router.replace(`/relation/${newType}/${userId.value}`)
}

onMounted(loadData)
watch(() => route.params.type, (newVal) => {
  type.value = newVal as string
  loadData()
})
</script>

<template>
  <div class="relation-view">
    <TheNavBar />
    
    <div class="segment-control-wrapper">
      <div class="segment-control">
        <div 
          class="segment-item" 
          :class="{ 'active': type === 'following' }"
          @click="toggleTab('following')"
        >关注</div>
        <div 
          class="segment-item" 
          :class="{ 'active': type === 'followers' }"
          @click="toggleTab('followers')"
        >粉丝</div>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
    </div>
    
    <div v-else class="list-container">
      <div v-if="items.length === 0" class="empty-state">
        <p class="text-secondary">暂无列表</p>
      </div>
      
      <div 
        v-for="item in items" 
        :key="item.userId" 
        class="user-item"
        @click="router.push(`/user/${item.userId}`)"
      >
        <img :src="item.avatar || 'https://via.placeholder.com/80'" class="user-avatar" />
        <div class="user-info">
          <p class="nickname">{{ item.nickname }}</p>
          <p class="bio text-secondary">{{ item.bio || '还没有签名' }}</p>
        </div>
        <div class="action-cell">
          <FollowButton :user-id="item.userId" :initial-state="item.isFollowing" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.relation-view {
  min-height: 100vh;
  padding-top: 44px;
  background-color: var(--apple-bg);
}

.segment-control-wrapper {
  padding: 16px;
  display: flex;
  justify-content: center;
}

.segment-control {
  width: 240px;
  height: 32px;
  background: #f5f5f7;
  border-radius: 8px;
  display: flex;
  padding: 2px;
}

.segment-item {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.segment-item.active {
  background: white;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}

.loading-state, .empty-state {
  padding: 100px 0;
  text-align: center;
}

.list-container {
  padding: 0 16px 40px;
}

.user-item {
  display: flex;
  align-items: center;
  padding: 12px 0;
  border-bottom: 0.5px solid rgba(0,0,0,0.05);
}

.user-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  margin-right: 12px;
  object-fit: cover;
}

.user-info {
  flex: 1;
}

.nickname {
  font-size: 16px;
  font-weight: 600;
}

.bio {
  font-size: 13px;
  margin-top: 2px;
}

.action-cell {
  width: 100px;
  transform: scale(0.8);
  transform-origin: right center;
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
