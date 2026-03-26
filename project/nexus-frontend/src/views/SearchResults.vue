<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchSearch, type SearchResultCardViewModel } from '@/api/search'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref(route.query.q as string || '')
const results = ref<SearchResultCardViewModel[]>([])
const loading = ref(true)

const performSearch = async () => {
  if (!keyword.value) {
    results.value = []
    loading.value = false
    return
  }

  loading.value = true
  try {
    const res = await fetchSearch({ q: keyword.value })
    results.value = res.items
  } catch (err) {
    console.error('Search failed', err)
  } finally {
    loading.value = false
  }
}

onMounted(performSearch)
watch(() => route.query.q, (newVal) => {
  keyword.value = typeof newVal === 'string' ? newVal : ''
  performSearch()
})

const onSelectPost = (post: SearchResultCardViewModel) => {
  router.push(`/content/${post.id}`)
}
</script>

<template>
  <div class="search-results-page">
    <TheNavBar />
    
    <div class="content-wrapper">
      <div class="page-header">
        <h1 class="text-large-title">搜索结果</h1>
        <p class="text-secondary">“{{ keyword }}” 的相关内容</p>
      </div>
      
      <div v-if="loading" class="loading-state">
        <div class="spinner"></div>
      </div>
      
      <div v-else-if="results.length === 0" class="empty-state">
        <p class="text-secondary">没有找到相关内容</p>
      </div>
      
      <div v-else class="results-list">
        <!-- 这里可以根据后端返回的类型区分展示用户或帖子 -->
        <PostCard 
          v-for="item in results" 
          :key="item.id" 
          :post="{
            id: item.id,
            title: item.title,
            body: item.body,
            author: item.author,
            image: item.image,
            isLiked: item.isLiked,
            reactionCount: item.reactionCount
          }" 
          @click="onSelectPost(item)"
        />
      </div>
    </div>
    
    <TheDock />
  </div>
</template>

<style scoped>
.search-results-page {
  height: 100vh;
  padding-top: 44px;
  background-color: var(--apple-bg);
  overflow-y: auto;
}

.content-wrapper {
  padding: 24px 0 120px;
}

.page-header {
  padding: 0 16px;
  margin-bottom: 24px;
}

.loading-state, .empty-state {
  padding: 100px 0;
  text-align: center;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid rgba(0,0,0,0.1);
  border-top-color: var(--apple-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
