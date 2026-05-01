<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { NotificationViewModel } from '@/types/notification'

defineProps<{
  notifications: NotificationViewModel[]
}>()

const emit = defineEmits<{
  (event: 'mark-read', notificationId: string): void
  (event: 'open-notification', notificationId: string): void
}>()
</script>

<template>
  <div class="space-y-3">
    <component
      v-for="notification in notifications"
      :key="notification.id"
      :is="notification.to ? RouterLink : 'article'"
      :to="notification.to"
      class="flex items-start justify-between gap-4 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)] transition hover:border-nx-primary/50 hover:shadow-[0_18px_40px_rgba(15,23,42,0.08)]"
      :class="notification.to ? 'block focus:outline-none focus:ring-2 focus:ring-nx-primary/30' : ''"
      @click="notification.to ? emit('open-notification', notification.id) : undefined"
    >
      <div class="min-w-0">
        <div class="flex items-center gap-2">
          <span
            v-if="notification.unread"
            data-test="notification-unread"
            class="inline-flex h-2.5 w-2.5 rounded-full bg-nx-accent"
          />
          <p class="text-base font-semibold text-nx-text">{{ notification.actorName }}</p>
        </div>
        <p class="mt-2 text-sm leading-6 text-nx-text-muted">{{ notification.actionText }}</p>
      </div>

      <div class="flex shrink-0 flex-col items-end gap-3">
        <span class="text-xs font-medium text-nx-text-muted">{{ notification.timeLabel }}</span>
        <button
          v-if="notification.unread"
          data-test="mark-notification-read"
          type="button"
          class="min-h-11 rounded-full border border-nx-border px-4 text-xs font-semibold text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
          @click.stop="emit('mark-read', notification.id)"
        >
          标为已读
        </button>
      </div>
    </component>
  </div>
</template>
