<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useFeedStore } from '@/store/feed'
import type { FeedCardViewModel } from '@/api/feed'
import PostCard from './PostCard.vue'
import StatePanel from '@/components/system/StatePanel.vue'

const props = defineProps<{
  featuredPostId?: string
}>()

const emit = defineEmits<{
  (event: 'select', post: FeedCardViewModel): void
}>()

const feedStore = useFeedStore()

const visiblePosts = computed(() =>
  feedStore.posts.filter((post) => post.postId !== props.featuredPostId)
)

onMounted(() => {
  if (feedStore.posts.length === 0) {
    void feedStore.fetchNextPage()
  }
})

const retryFetch = () => {
  void feedStore.refresh()
}
</script>

<template>
  <section class="grid gap-5" aria-label="推荐内容流">
    <StatePanel
      v-if="feedStore.error && feedStore.posts.length === 0"
      variant="request-failure"
      title="内容暂时没有加载出来"
      body="时间线请求失败了，你可以重新尝试同步内容。"
      primary-label="重新加载"
      @primary="retryFetch"
    />

    <div v-else-if="visiblePosts.length > 0" class="grid gap-5 xl:grid-cols-12">
      <div
        v-for="(post, index) in visiblePosts"
        :key="post.postId"
        :class="index % 4 === 0 ? 'xl:col-span-7' : index % 4 === 1 ? 'xl:col-span-5' : 'xl:col-span-4'"
      >
        <PostCard
          :post="{
            id: post.postId,
            title: post.title,
            body: post.body,
            author: post.author,
            image: post.image,
            isLiked: post.isLiked,
            reactionCount: post.reactionCount,
            commentCount: post.commentCount
          }"
          :variant="index % 3 === 0 ? 'feature' : 'standard'"
          @click="emit('select', post)"
        />
      </div>
    </div>

    <StatePanel
      v-else-if="!feedStore.loading"
      variant="empty"
      title="还没有可展示的内容"
      body="等时间线出现新的内容后，这里会逐步填满。"
    />

    <div
      v-if="feedStore.loading"
      class="flex min-h-[76px] items-center justify-center gap-3 rounded-[28px] border border-outline-variant/10 bg-surface-container-low/70 text-sm text-on-surface-variant"
    >
      <div class="spinner" />
      <span>正在整理内容...</span>
    </div>

    <button
      v-if="!feedStore.loading && feedStore.hasMore"
      type="button"
      class="secondary-btn justify-self-center"
      @click="feedStore.fetchNextPage()"
    >
      加载更多
    </button>
  </section>
</template>
