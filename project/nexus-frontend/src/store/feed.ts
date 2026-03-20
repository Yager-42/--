import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchTimeline, type FeedItemDTO } from '@/api/feed'

export const useFeedStore = defineStore('feed', () => {
  const posts = ref<FeedItemDTO[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const hasMore = ref(true)

  const fetchNextPage = async () => {
    if (loading.value || !hasMore.value) return
    
    loading.value = true
    try {
      const res: any = await fetchTimeline({
        cursor: nextCursor.value || undefined,
        limit: 10
      })
      
      if (res.items && res.items.length > 0) {
        posts.value = [...posts.value, ...res.items]
        nextCursor.value = res.nextCursor
      } else {
        hasMore.value = false
      }
    } catch (err) {
      console.error('Failed to fetch timeline', err)
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
    fetchNextPage,
    refresh
  }
})
