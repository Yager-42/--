import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchTimeline, type FeedCardViewModel } from '@/api/feed'

export const useFeedStore = defineStore('feed', () => {
  const posts = ref<FeedCardViewModel[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const hasMore = ref(true)
  const error = ref<string | null>(null)

  const mergePosts = (incoming: FeedCardViewModel[]) => {
    const seen = new Set(posts.value.map((post) => post.postId))
    const nextItems = incoming.filter((post) => {
      if (seen.has(post.postId)) {
        return false
      }
      seen.add(post.postId)
      return true
    })
    posts.value = [...posts.value, ...nextItems]
  }

  const fetchNextPage = async () => {
    if (loading.value || !hasMore.value) return

    loading.value = true
    error.value = null
    try {
      const res = await fetchTimeline({
        cursor: nextCursor.value || undefined,
        limit: 10
      })

      mergePosts(res.items)
      nextCursor.value = res.page.nextCursor
      hasMore.value = res.page.hasMore

      if (res.items.length === 0 && res.page.nextCursor === null) {
        hasMore.value = false
      }
    } catch (err) {
      console.error('Failed to fetch timeline', err)
      error.value = err instanceof Error ? err.message : '内容加载失败，请稍后重试'
    } finally {
      loading.value = false
    }
  }

  const refresh = async () => {
    posts.value = []
    nextCursor.value = null
    hasMore.value = true
    await fetchNextPage()
  }

  return {
    posts,
    loading,
    hasMore,
    error,
    fetchNextPage,
    refresh
  }
})
