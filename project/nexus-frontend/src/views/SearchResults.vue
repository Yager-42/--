<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchSearch, type SearchResultCardViewModel } from '@/api/search'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const results = ref<SearchResultCardViewModel[]>([])
const loading = ref(false)
const error = ref('')

const performSearch = async () => {
  if (!keyword.value.trim()) {
    results.value = []
    error.value = ''
    return
  }

  loading.value = true
  error.value = ''
  try {
    const res = await fetchSearch({ q: keyword.value.trim(), size: 20 })
    results.value = res.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : '搜索失败，请稍后重试'
    results.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => route.query.q,
  (val) => {
    keyword.value = typeof val === 'string' ? val : ''
    void performSearch()
  },
  { immediate: true }
)

const openPost = (post: SearchResultCardViewModel) => {
  void router.push(`/content/${post.id}`)
}
</script>

<template>
  <div class="page-shell with-full-nav">
    <TheNavBar />

    <main class="page-content results-page">
      <header class="results-header">
        <h1 class="text-large-title">搜索结果</h1>
        <p class="text-secondary">关键词：{{ keyword || '未输入' }}</p>
      </header>

      <section v-if="loading" class="state-card">
        <div class="spinner"></div>
        <span>正在搜索...</span>
      </section>

      <section v-else-if="error" class="state-card error">
        {{ error }}
      </section>

      <section v-else-if="results.length === 0" class="state-card">
        没有找到相关内容
      </section>

      <section v-else class="results-grid">
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
          @click="openPost(item)"
        />
      </section>
    </main>

    <TheDock />
  </div>
</template>

<style scoped>
.results-page {
  display: grid;
  gap: 14px;
}

.results-header {
  padding: 8px 2px;
}

.results-grid {
  display: grid;
  gap: 14px;
}

.state-card {
  min-height: 120px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-secondary);
}

.error {
  color: var(--brand-danger);
}
</style>


