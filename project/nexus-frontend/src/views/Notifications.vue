<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchNotifications, markAllAsRead, type NotificationDTO } from '@/api/notification'
import { useAuthStore } from '@/store/auth'
import NotificationItem from '@/components/NotificationItem.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import TheDock from '@/components/TheDock.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'

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
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main page-main--dock">
      <section class="grid gap-6">
        <header class="grid gap-4 md:flex md:items-end md:justify-between">
          <div class="grid gap-3">
            <p class="section-kicker">Inbox</p>
            <h1 class="section-title">Quiet updates, not loud interruptions.</h1>
          </div>

          <ZenButton v-if="hasUnread" variant="secondary" @click="handleReadAll">
            全部已读
          </ZenButton>
        </header>

        <StatePanel
          v-if="error"
          variant="request-failure"
          :body="error"
          primary-label="重新加载"
          @primary="loadNotifications"
        />

        <StatePanel
          v-else-if="loading && notifications.length === 0"
          variant="loading"
          title="正在整理通知"
          body="你的通知正在按时间顺序安静地排好。"
        />

        <StatePanel
          v-else-if="notifications.length === 0"
          variant="empty"
          title="现在还没有新的提醒"
          body="当有人关注、评论或点赞时，这里会出现新的动态。"
        />

        <section v-else class="grid gap-4">
          <NotificationItem
            v-for="item in notifications"
            :key="item.notificationId"
            :notification="item"
          />

          <ZenButton
            v-if="hasMore"
            variant="secondary"
            class="justify-self-center"
            :disabled="loading"
            @click="loadNotifications"
          >
            {{ loading ? '加载中...' : '加载更多' }}
          </ZenButton>
        </section>
      </section>
    </main>

    <TheDock />
  </div>
</template>
