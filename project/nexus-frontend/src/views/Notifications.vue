<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchNotifications, markAllAsRead, type NotificationDTO } from '@/api/notification'
import { useAuthStore } from '@/store/auth'
import NotificationItem from '@/components/NotificationItem.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'

const authStore = useAuthStore()
const notifications = ref<NotificationDTO[]>([])
const loading = ref(true)
const nextCursor = ref<string | null>(null)
const hasMore = ref(true)

const loadNotifications = async () => {
  if (!authStore.userId || loading.value || !hasMore.value) {
    loading.value = false
    return
  }

  loading.value = true
  try {
    const res = await fetchNotifications({
      userId: authStore.userId,
      cursor: nextCursor.value || undefined
    })
    notifications.value = [...notifications.value, ...res.notifications]
    nextCursor.value = res.page.nextCursor
    hasMore.value = res.page.hasMore
  } catch (err) {
    console.error('Fetch notifications failed', err)
  } finally {
    loading.value = false
  }
}

const handleReadAll = async () => {
  try {
    await markAllAsRead()
    notifications.value = notifications.value.map((item) => ({
      ...item,
      isRead: true,
      hasUnread: false
    }))
  } catch (err) {
    console.error('Mark all as read failed', err)
  }
}

onMounted(loadNotifications)
</script>

<template>
  <div class="notifications-page">
    <TheNavBar />
    
    <div class="content-wrapper">
      <div class="page-header">
        <h1 class="text-large-title">通知中心</h1>
        <button v-if="notifications.some(n => !n.isRead)" class="read-all-btn" @click="handleReadAll">全部已读</button>
      </div>
      
      <div v-if="loading && notifications.length === 0" class="loading-state">
        <div class="spinner"></div>
      </div>
      
      <div v-else-if="notifications.length === 0" class="empty-state">
        <p class="text-secondary">暂无通知</p>
      </div>
      
      <div v-else class="notification-list">
        <NotificationItem 
          v-for="n in notifications" 
          :key="n.notificationId" 
          :notification="n" 
        />
      </div>
    </div>
    
    <TheDock />
  </div>
</template>

<style scoped>
.notifications-page {
  height: 100vh;
  padding-top: 44px;
  background-color: var(--apple-bg);
  overflow-y: auto;
}

.content-wrapper {
  padding: 24px 16px 120px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 24px;
}

.read-all-btn {
  background: none;
  border: none;
  color: var(--apple-accent);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
}

.loading-state, .empty-state {
  padding: 100px 0;
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
