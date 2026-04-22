import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useUiStore = defineStore('ui', () => {
  const unreadNotificationCount = ref(0)
  const hasUnreadNotifications = computed(() => unreadNotificationCount.value > 0)

  function setUnreadNotificationCount(count: number) {
    unreadNotificationCount.value = Math.max(0, count)
  }

  return {
    unreadNotificationCount,
    hasUnreadNotifications,
    setUnreadNotificationCount
  }
})
