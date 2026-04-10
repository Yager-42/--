<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchSearch, type SearchResultCardViewModel } from '@/api/search'
import PostCard from '@/components/PostCard.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import TheDock from '@/components/TheDock.vue'
import TheNavBar from '@/components/TheNavBar.vue'

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
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main page-main--dock">
      <section class="grid gap-6">
        <header class="grid gap-3">
          <p class="section-kicker">Search</p>
          <h1 class="section-title">Results for {{ keyword || 'everything' }}</h1>
          <p class="section-copy">
            当前搜索结果仅基于后端真实可返回的内容实体，不扩展成伪多类型检索。
          </p>
        </header>

        <StatePanel
          v-if="loading"
          variant="loading"
          title="正在整理搜索结果"
          body="我们正在根据关键词匹配最新内容。"
        />

        <StatePanel
          v-else-if="error"
          variant="request-failure"
          :body="error"
          primary-label="返回首页"
          @primary="router.push('/')"
        />

        <StatePanel
          v-else-if="results.length === 0"
          variant="no-results"
          body="可以尝试缩短关键词，或改用作者名、内容主题再次搜索。"
        />

        <section v-else class="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
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
            variant="feature"
            @click="openPost(item)"
          />
        </section>
      </section>
    </main>

    <TheDock />
  </div>
</template>
