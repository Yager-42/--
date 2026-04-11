<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchSearch, type SearchResultCardViewModel } from '@/api/search'
import PostCard from '@/components/PostCard.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import StatePanel from '@/components/system/StatePanel.vue'

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

const submitSearch = () => {
  const nextKeyword = keyword.value.trim()
  void router.push(nextKeyword ? { path: '/search', query: { q: nextKeyword } } : '/search')
}
</script>

<template>
  <PrototypeShell>
    <article data-prototype-search class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="grid gap-6 lg:grid-cols-[minmax(0,1fr),22rem] lg:items-end">
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Search
            </p>
            <h1 class="max-w-[10ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              {{ keyword ? `Results for ${keyword}` : 'Search the archive' }}
            </h1>
            <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
              当前搜索结果仅基于后端真实可返回的内容实体，不扩展成伪多类型检索。
            </p>
          </div>

          <form
            class="flex items-center gap-3 rounded-full border border-prototype-line bg-prototype-surface px-5 py-3"
            @submit.prevent="submitSearch"
          >
            <input
              v-model="keyword"
              type="search"
              class="w-full border-none bg-transparent text-sm text-prototype-ink outline-none placeholder:text-prototype-muted/70"
              placeholder="Search by title, author, or topic"
            >
            <button
              type="submit"
              class="rounded-full bg-prototype-ink px-4 py-2 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-surface transition hover:opacity-90"
            >
              Search
            </button>
          </form>
        </div>
      </PrototypeContainer>

      <PrototypeContainer v-if="loading" width="content">
        <StatePanel
          variant="loading"
          title="正在整理搜索结果"
          body="我们正在根据关键词匹配最新内容。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="error" width="content">
        <StatePanel
          variant="request-failure"
          :body="error"
          primary-label="返回首页"
          @primary="router.push('/')"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="results.length === 0" width="content">
        <StatePanel
          variant="no-results"
          body="可以尝试缩短关键词，或改用作者名、内容主题再次搜索。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else class="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
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
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
