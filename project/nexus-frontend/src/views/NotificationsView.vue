<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import NotificationList from '@/components/notifications/NotificationList.vue'
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '@/services/api/notificationApi'
import { useUiStore } from '@/stores/ui'
import type { NotificationViewModel } from '@/types/notification'

const props = defineProps<{
  notifications?: NotificationViewModel[]
}>()

const remoteNotifications = ref<NotificationViewModel[]>([])
const isLoading = ref(false)
const uiStore = useUiStore()
const { unreadNotificationCount } = storeToRefs(uiStore)

const notifications = computed(() => props.notifications ?? remoteNotifications.value)

function updateNotificationState(notificationId: string, unread: boolean) {
  remoteNotifications.value = remoteNotifications.value.map((item) =>
    item.id === notificationId
      ? {
          ...item,
          unread
        }
      : item
  )

  uiStore.setUnreadNotificationCount(remoteNotifications.value.filter((item) => item.unread).length)
}

onMounted(async () => {
  if (props.notifications?.length) {
    return
  }

  isLoading.value = true

  try {
    const response = await fetchNotifications()
    remoteNotifications.value = response.notifications
    uiStore.setUnreadNotificationCount(response.notifications.filter((item) => item.unread).length)
  } finally {
    isLoading.value = false
  }
})

async function handleMarkAllRead() {
  await markAllNotificationsRead()
  remoteNotifications.value = remoteNotifications.value.map((item) => ({
    ...item,
    unread: false
  }))
  uiStore.setUnreadNotificationCount(0)
}

async function handleMarkRead(notificationId: string) {
  await markNotificationRead(notificationId)
  updateNotificationState(notificationId, false)
}

async function handleOpenNotification(notificationId: string) {
  const notification = notifications.value.find((item) => item.id === notificationId)

  if (!notification?.unread) {
    return
  }

  await handleMarkRead(notificationId)
}
</script>

<template>
  <section class="space-y-5">
    <header class="flex items-center justify-between gap-4 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <div>
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Inbox</p>
        <h1 class="mt-2 font-headline text-3xl font-semibold text-nx-text">通知</h1>
      </div>

      <button
        type="button"
        class="min-h-11 rounded-full border border-nx-border px-5 text-sm font-medium text-nx-text"
        @click="handleMarkAllRead"
      >
        全部标为已读<span v-if="unreadNotificationCount" class="ml-2 text-nx-accent">({{ unreadNotificationCount }})</span>
      </button>
    </header>

    <LoadingSkeleton v-if="isLoading" />

    <NotificationList
      v-if="notifications.length"
      :notifications="notifications"
      @mark-read="handleMarkRead"
      @open-notification="handleOpenNotification"
    />

    <EmptyState
      v-else-if="!isLoading"
      title="还没有新的通知"
      description="当有人与你互动时，这里会按时间顺序展示提醒。"
    />
  </section>
</template>
