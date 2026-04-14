<script setup lang="ts">
import { computed } from 'vue'
import { markAsRead, type NotificationDTO } from '@/api/notification'
import { useRouter } from 'vue-router'

const props = defineProps<{
  notification: NotificationDTO
}>()

const emit = defineEmits<{
  (event: 'read', notificationId: string): void
}>()

const router = useRouter()

const typeText = computed(() => {
  switch (props.notification.type) {
    case 'LIKE':
      return '点赞了你的内容'
    case 'COMMENT':
      return '评论了你'
    case 'FOLLOW':
      return '关注了你'
    default:
      return '向你发送了一条通知'
  }
})

const timeText = computed(() => {
  if (!props.notification.createTime) return '刚刚'
  return new Date(props.notification.createTime).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
})

const openTarget = async () => {
  if (props.notification.hasUnread) {
    try {
      await markAsRead(props.notification.notificationId)
      emit('read', props.notification.notificationId)
    } catch (error) {
      console.error('mark as read failed', error)
    }
  }

  if (props.notification.type === 'FOLLOW' && props.notification.senderId) {
    void router.push(`/user/${props.notification.senderId}`)
    return
  }

  if (props.notification.targetId) {
    void router.push(`/content/${props.notification.targetId}`)
    return
  }

  void router.push('/notifications')
}
</script>

<template>
  <article
    class="grid min-h-[96px] cursor-pointer grid-cols-[3rem,minmax(0,1fr),auto] items-center gap-4 rounded-[28px] border p-4 transition"
    :class="notification.hasUnread ? 'border-outline-variant/25 bg-surface-container-lowest' : 'border-outline-variant/10 bg-white/75'"
    @click="openTarget"
  >
    <img :src="notification.senderAvatar || 'https://via.placeholder.com/80'" class="h-12 w-12 rounded-full object-cover" alt="avatar">

    <div class="grid min-w-0 gap-1.5">
      <p class="text-[11px] font-semibold uppercase tracking-[0.2em] text-on-surface-variant">{{ timeText }}</p>
      <p class="flex flex-wrap gap-2 leading-7 text-on-surface">
        <strong class="font-semibold">{{ notification.senderName }}</strong>
        <span>{{ typeText }}</span>
      </p>
      <p v-if="notification.content" class="line-clamp-2 text-sm leading-7 text-on-surface-variant">{{ notification.content }}</p>
    </div>

    <span v-if="notification.hasUnread" class="h-2.5 w-2.5 rounded-full bg-primary" aria-label="未读" />
  </article>
</template>
