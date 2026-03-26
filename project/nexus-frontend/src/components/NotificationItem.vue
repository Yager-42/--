<script setup lang="ts">
import { markAsRead, type NotificationDTO } from '@/api/notification'
import { useRouter } from 'vue-router'

const props = defineProps<{
  notification: NotificationDTO;
}>();

const router = useRouter();

const handleClick = async () => {
  if (props.notification.hasUnread) {
    try {
      await markAsRead(props.notification.notificationId);
      props.notification.isRead = true;
      props.notification.hasUnread = false;
    } catch (err) {
      console.error('Mark as read failed', err);
    }
  }
  
  if (props.notification.type === 'FOLLOW') {
    router.push(`/user/${props.notification.senderId}`);
    return;
  }

  if (props.notification.targetId) {
    router.push(`/content/${props.notification.targetId}`);
  }
}

const getActionText = (type: string) => {
  switch (type) {
    case 'LIKE': return '赞了你的帖子';
    case 'COMMENT': return '评论了你';
    case 'FOLLOW': return '开始关注你';
    default: return '发来了通知';
  }
}

const getActionIcon = (type: string) => {
  switch (type) {
    case 'LIKE': return '❤️';
    case 'COMMENT': return '💬';
    case 'FOLLOW': return '👤';
    default: return '🔔';
  }
}
</script>

<template>
  <div class="notification-item" :class="{ 'unread': notification.hasUnread }" @click="handleClick">
    <div class="unread-dot"></div>
    <img :src="notification.senderAvatar || 'https://via.placeholder.com/80'" class="sender-avatar" />
    <div class="content">
      <div class="main-text">
        <span class="sender-name">{{ notification.senderName }}</span>
        <span class="action-text text-secondary">{{ getActionText(notification.type) }}</span>
      </div>
      <p v-if="notification.content" class="action-body text-body">{{ notification.content }}</p>
      <span class="time text-secondary">刚刚</span>
    </div>
    <div class="action-icon">{{ getActionIcon(notification.type) }}</div>
  </div>
</template>

<style scoped>
.notification-item {
  display: flex;
  align-items: center;
  padding: 16px;
  background: white;
  margin-bottom: 8px;
  border-radius: 16px;
  position: relative;
  transition: background 0.2s ease;
  border: 0.5px solid rgba(0,0,0,0.05);
}

.notification-item:active {
  background: #f5f5f7;
}

.unread-dot {
  width: 6px;
  height: 6px;
  background: var(--apple-accent);
  border-radius: 50%;
  position: absolute;
  left: 6px;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.unread .unread-dot {
  opacity: 1;
}

.sender-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  margin-right: 12px;
  object-fit: cover;
}

.content {
  flex: 1;
}

.sender-name {
  font-weight: 600;
  margin-right: 6px;
}

.main-text {
  font-size: 15px;
}

.action-body {
  font-size: 14px;
  margin-top: 4px;
  line-height: 1.4;
}

.time {
  font-size: 12px;
  margin-top: 4px;
}

.action-icon {
  font-size: 16px;
  opacity: 0.6;
}
</style>
