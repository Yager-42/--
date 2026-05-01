<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import EmptyState from '@/components/common/EmptyState.vue'
import FeedComposerEntry from '@/components/feed/FeedComposerEntry.vue'
import TimelineList from '@/components/feed/TimelineList.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import { fetchTimeline } from '@/services/api/feedApi'
import type { FeedCardViewModel } from '@/types/viewModels'

const props = defineProps<{
  initialItems?: FeedCardViewModel[]
}>()

const remoteItems = ref<FeedCardViewModel[]>([])
const isLoading = ref(false)
const activeFeedType = ref<'FOLLOW' | 'POPULAR' | 'RECOMMEND'>('FOLLOW')

const items = computed(() => props.initialItems ?? remoteItems.value)

const timelineTabs = [
  { key: 'FOLLOW', label: '关注' },
  { key: 'POPULAR', label: '热门' },
  { key: 'RECOMMEND', label: '为你推荐' }
] as const

async function loadTimeline() {
  if (props.initialItems?.length) {
    return
  }

  isLoading.value = true

  try {
    const response = await fetchTimeline({
      feedType: activeFeedType.value
    })
    remoteItems.value = response.items
  } finally {
    isLoading.value = false
  }
}

async function switchFeed(feedType: 'FOLLOW' | 'POPULAR' | 'RECOMMEND') {
  if (activeFeedType.value === feedType && remoteItems.value.length) {
    return
  }

  activeFeedType.value = feedType
  await loadTimeline()
}

onMounted(async () => {
  await loadTimeline()
})
</script>

<template>
  <section class="space-y-5">
    <FeedComposerEntry />

    <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-4 shadow-[var(--nx-shadow-card)]">
      <div class="flex flex-wrap gap-3">
        <button
          v-for="tab in timelineTabs"
          :key="tab.key"
          :data-test="`timeline-filter-${tab.key}`"
          type="button"
          class="min-h-11 rounded-full border px-4 text-sm font-medium transition"
          :class="
            activeFeedType === tab.key
              ? 'border-nx-primary bg-nx-primary text-white'
              : 'border-nx-border bg-white text-nx-text hover:border-nx-primary hover:text-nx-primary'
          "
          @click="switchFeed(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>
    </section>

    <LoadingSkeleton v-if="isLoading" />

    <TimelineList v-if="items.length" :items="items" />

    <EmptyState
      v-else-if="!isLoading"
      title="你的时间线还没有内容"
      description="连接更多用户或稍后刷新，这里会展示内容流。"
    />
  </section>
</template>
