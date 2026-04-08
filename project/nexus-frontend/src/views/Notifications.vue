<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchNotifications, markAllAsRead, type NotificationDTO } from '@/api/notification'
import { useAuthStore } from '@/store/auth'
import NotificationItem from '@/components/NotificationItem.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'

const authStore = useAuthStore()
const notifications = ref<NotificationDTO[]>([])
const loading = ref(false)
const error = ref('')
const nextCursor = ref<string | null>(null)
const hasMore = ref(true)

const hasUnread = computed(() => notifications.value.some((item) => !item.isRead))

const loadNotifications = async () => {
  if (!authStore.userId || loading.value || !hasMore.value) return

  loading.value = true
  error.value = ''
  try {
    const res = await fetchNotifications({
      userId: authStore.userId,
      cursor: nextCursor.value || undefined
    })
    notifications.value = [...notifications.value, ...res.notifications]
    nextCursor.value = res.page.nextCursor
    hasMore.value = res.page.hasMore
  } catch (e) {
    error.value = e instanceof Error ? e.message : '通知加载失败'
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
  } catch (e) {
    error.value = e instanceof Error ? e.message : '全部已读失败'
  }
}

onMounted(() => {
  void loadNotifications()
})
</script>

<template>
  <div class="page-shell with-full-nav">
    <TheNavBar />

    <main class="page-content notifications-page">
      <header class="page-header">
        <h1 class="text-large-title">通知中心</h1>
        <button v-if="hasUnread" class="secondary-btn read-all-btn" type="button" @click="handleReadAll">
          全部已读
        </button>
      </header>

      <section v-if="error" class="state-card error">
        {{ error }}
      </section>

      <section v-else-if="loading && notifications.length === 0" class="state-card">
        <div class="spinner"></div>
        正在加载通知...
      </section>

      <section v-else-if="notifications.length === 0" class="state-card">
        暂无通知
      </section>

      <section v-else class="list">
        <NotificationItem v-for="item in notifications" :key="item.notificationId" :notification="item" />

        <button v-if="hasMore" class="secondary-btn more-btn" type="button" :disabled="loading" @click="loadNotifications">
          {{ loading ? '加载中...' : '加载更多' }}
        </button>
      </section>
    </main>

    <TheDock />
  </div>
</template>

<style scoped>
.notifications-page {
  display: grid;
  gap: 14px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.read-all-btn,
.more-btn {
  min-width: 110px;
  padding: 0 14px;
}

.list {
  display: grid;
  gap: 10px;
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


