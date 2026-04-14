<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchNotifications, markAllAsRead, type NotificationDTO } from '@/api/notification'
import { useAuthStore } from '@/store/auth'
import NotificationItem from '@/components/NotificationItem.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'

const authStore = useAuthStore()
const notifications = ref<NotificationDTO[]>([])
const loading = ref(false)
const error = ref('')
const nextCursor = ref<string | null>(null)
const hasMore = ref(true)

const hasUnread = computed(() => notifications.value.some((item) => !item.isRead))
const unreadNotifications = computed(() => notifications.value.filter((item) => !item.isRead))
const earlierNotifications = computed(() => notifications.value.filter((item) => item.isRead))

const markLocalRead = (notificationId: string) => {
  notifications.value = notifications.value.map((item) =>
    item.notificationId === notificationId
      ? {
          ...item,
          hasUnread: false,
          isRead: true
        }
      : item
  )
}

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

watch(
  () => authStore.userId,
  (userId) => {
    if (!userId || notifications.value.length > 0 || loading.value) {
      return
    }

    void loadNotifications()
  },
  { immediate: true }
)
</script>

<template>
  <PrototypeShell>
    <article data-prototype-notifications class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="grid gap-6 lg:grid-cols-[minmax(0,1fr),auto] lg:items-end">
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Inbox
            </p>
            <h1 class="font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              Quiet updates, not loud interruptions.
            </h1>
            <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
              只展示真实通知流，不伪造额外的摘要层或推送聚合。
            </p>
          </div>

          <div class="flex items-center gap-3">
            <div class="rounded-full border border-prototype-line bg-prototype-surface px-4 py-2 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
              {{ unreadNotifications.length }} unread
            </div>
            <ZenButton v-if="hasUnread" variant="secondary" @click="handleReadAll">
              全部已读
            </ZenButton>
          </div>
        </div>
      </PrototypeContainer>

      <PrototypeContainer v-if="error" width="content">
        <StatePanel
          variant="request-failure"
          :body="error"
          primary-label="重新加载"
          @primary="loadNotifications"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="loading && notifications.length === 0" width="content">
        <StatePanel
          variant="loading"
          title="正在整理通知"
          body="你的通知正在按时间顺序安静地排好。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="notifications.length === 0" width="content">
        <StatePanel
          variant="empty"
          title="现在还没有新的提醒"
          body="当有人关注、评论或点赞时，这里会出现新的动态。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else width="content" class="space-y-10">
        <section v-if="unreadNotifications.length > 0" class="space-y-4">
          <div class="flex items-center justify-between gap-4">
            <div>
              <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
                Unread
              </p>
              <h2 class="mt-2 font-headline text-3xl tracking-[-0.03em] text-prototype-ink">
                Fresh activity worth opening.
              </h2>
            </div>
            <span class="rounded-full bg-prototype-surface px-4 py-2 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
              {{ unreadNotifications.length }} new
            </span>
          </div>

          <div class="space-y-4 rounded-[2rem] border border-prototype-line bg-prototype-surface p-5">
            <NotificationItem
              v-for="item in unreadNotifications"
              :key="item.notificationId"
              :notification="item"
              @read="markLocalRead"
            />
          </div>
        </section>

        <section v-if="earlierNotifications.length > 0" class="space-y-4">
          <div>
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Earlier
            </p>
            <h2 class="mt-2 font-headline text-3xl tracking-[-0.03em] text-prototype-ink">
              Older signals, kept quiet.
            </h2>
          </div>

          <div class="space-y-4 rounded-[2rem] border border-prototype-line bg-prototype-bg/60 p-5">
            <NotificationItem
              v-for="item in earlierNotifications"
              :key="item.notificationId"
              :notification="item"
              @read="markLocalRead"
            />
          </div>
        </section>

        <div class="flex justify-center">
          <ZenButton
            v-if="hasMore"
            variant="secondary"
            :disabled="loading"
            @click="loadNotifications"
          >
            {{ loading ? '加载中...' : 'Load Older Notifications' }}
          </ZenButton>
        </div>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
