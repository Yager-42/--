<script setup lang="ts">
import { computed } from 'vue'
import { markAsRead, type NotificationDTO } from '@/api/notification'
import { useRouter } from 'vue-router'

const props = defineProps<{
  notification: NotificationDTO
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
      return '给你发送了一条通知'
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
      props.notification.hasUnread = false
      props.notification.isRead = true
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
  }
}
</script>

<template>
  <article class="notification-item" :class="{ unread: notification.hasUnread }" @click="openTarget">
    <img :src="notification.senderAvatar || 'https://via.placeholder.com/80'" class="avatar" alt="avatar">

    <div class="main">
      <p class="line">
        <strong>{{ notification.senderName }}</strong>
        <span class="text-secondary">{{ typeText }}</span>
      </p>
      <p class="content" v-if="notification.content">{{ notification.content }}</p>
      <p class="time text-secondary">{{ timeText }}</p>
    </div>

    <span class="dot" v-if="notification.hasUnread" aria-label="未读"></span>
  </article>
</template>

<style scoped>
.notification-item {
  min-height: 78px;
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  padding: 12px;
  background: #fff;
  display: grid;
  grid-template-columns: 44px 1fr auto;
  gap: 10px;
  align-items: center;
  cursor: pointer;
}

.notification-item.unread {
  border-color: var(--border-strong);
  background: #fff6f8;
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  object-fit: cover;
}

.main {
  min-width: 0;
}

.line {
  display: flex;
  gap: 6px;
  align-items: baseline;
  flex-wrap: wrap;
}

.content {
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 0.9rem;
  line-height: 1.5;
}

.time {
  margin-top: 4px;
  font-size: 0.8rem;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-primary);
}
</style>
