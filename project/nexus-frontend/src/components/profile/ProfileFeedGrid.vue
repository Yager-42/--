<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { FeedCardViewModel } from '@/api/feed'
import ZenButton from '@/components/primitives/ZenButton.vue'
import StatePanel from '@/components/system/StatePanel.vue'

const props = withDefaults(
  defineProps<{
    items: FeedCardViewModel[]
    loading?: boolean
    error?: string
    hasMore?: boolean
    loadingMore?: boolean
  }>(),
  {
    loading: false,
    error: '',
    hasMore: false,
    loadingMore: false
  }
)

const emit = defineEmits<{
  (event: 'load-more'): void
  (event: 'retry'): void
}>()

const router = useRouter()

const formattedPosts = computed(() =>
  props.items.map((item) => ({
    ...item,
    dateLabel: new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    }).format(item.createTime)
  }))
)

const openPost = (postId: string) => {
  void router.push(`/content/${postId}`)
}
</script>

<template>
  <section data-profile-feed-grid class="space-y-6">
    <div class="space-y-2">
      <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
        Author archive
      </p>
      <h3 class="font-headline text-3xl tracking-[-0.03em] text-prototype-ink">
        A uniform feed rhythm for every post on this profile.
      </h3>
      <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
        The profile archive now uses the same card cadence as home, so identity, discovery, and
        content stay on one visual system.
      </p>
    </div>

    <StatePanel
      v-if="props.loading"
      variant="loading"
      title="正在加载作者内容"
      body="资料页正在同步作者的最新帖子。"
    />

    <StatePanel
      v-else-if="props.error && props.items.length === 0"
      variant="request-failure"
      title="作者内容暂时不可用"
      :body="props.error"
      action-label="重试"
      @action="emit('retry')"
    />

    <StatePanel
      v-else-if="props.items.length === 0"
      variant="empty"
      title="这个档案还没有公开内容"
      body="作者的 archive 目前为空，稍后再来查看。"
    />

    <template v-else>
      <div
        v-if="props.error"
        class="rounded-[1.1rem] border border-error/20 bg-[rgba(158,66,44,0.08)] px-4 py-3 text-sm leading-7 text-error"
      >
        {{ props.error }}
      </div>

      <div class="grid gap-x-10 gap-y-12 md:grid-cols-2 xl:grid-cols-3">
        <button
          v-for="item in formattedPosts"
          :key="item.postId"
          type="button"
          class="group text-left transition hover:-translate-y-1.5"
          @click="openPost(item.postId)"
        >
          <div class="mb-5 aspect-[4/5] overflow-hidden rounded-[1.2rem] bg-prototype-bg shadow-[0_22px_44px_rgba(27,31,31,0.08)]">
            <img
              :src="item.image"
              :alt="item.title"
              class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.04]"
            >
          </div>
          <div class="space-y-3 px-1">
            <div class="flex items-start justify-between gap-4">
              <h4 class="max-w-[16ch] font-headline text-[1.45rem] leading-8 tracking-[-0.03em] text-prototype-ink">
                {{ item.title }}
              </h4>
              <span class="rounded-full bg-prototype-surface px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                {{ item.dateLabel }}
              </span>
            </div>
            <p class="text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted">
              {{ item.author }}
            </p>
          </div>
        </button>
      </div>

      <div v-if="props.hasMore" class="flex justify-center">
        <ZenButton
          variant="secondary"
          :disabled="props.loadingMore"
          @click="emit('load-more')"
        >
          {{ props.loadingMore ? '加载中...' : '加载更多帖子' }}
        </ZenButton>
      </div>
    </template>
  </section>
</template>
