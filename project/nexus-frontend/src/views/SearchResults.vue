<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchSearch, type SearchResultCardViewModel } from '@/api/search'
import SearchInput from '@/components/SearchInput.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

interface CuratorSummary {
  id: string
  name: string
  avatar: string
  descriptor: string
  resultCount: number
}

interface CollectionSummary {
  id: string
  title: string
  cover: string
  meta: string
}

const route = useRoute()
const router = useRouter()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const searchExpanded = ref(false)
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

const featuredResult = computed(() => results.value[0] ?? null)
const galleryResults = computed(() => results.value.slice(1))
const relatedCurators = computed<CuratorSummary[]>(() => {
  const grouped = new Map<string, CuratorSummary>()

  results.value.forEach((item) => {
    const current = grouped.get(item.author)
    if (current) {
      current.resultCount += 1
      return
    }

    grouped.set(item.author, {
      id: item.author,
      name: item.author,
      avatar: item.authorAvatar,
      descriptor: item.tags[0] ?? 'Quiet archive',
      resultCount: 1
    })
  })

  return Array.from(grouped.values()).slice(0, 3)
})
const curatedCollections = computed<CollectionSummary[]>(() => {
  const grouped = new Map<string, CollectionSummary>()

  results.value.forEach((item) => {
    item.tags.forEach((tag) => {
      const key = tag.trim()
      if (!key) {
        return
      }

      const current = grouped.get(key)
      if (current) {
        const nextCount = Number(current.meta.split(' ')[0]) + 1
        current.meta = `${nextCount} posts`
        return
      }

      grouped.set(key, {
        id: key,
        title: key,
        cover: item.image,
        meta: '1 posts'
      })
    })
  })

  return Array.from(grouped.values()).slice(0, 3)
})
const resultsLabel = computed(() => `${results.value.length} results`)

const openPost = (post: SearchResultCardViewModel) => {
  void router.push(`/content/${post.id}`)
}

const submitSearch = (nextValue?: string) => {
  const nextKeyword = (nextValue ?? keyword.value).trim()
  void router.push(nextKeyword ? { path: '/search', query: { q: nextKeyword } } : '/search')
}
</script>

<template>
  <PrototypeShell>
    <article data-prototype-search class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="grid gap-6 lg:grid-cols-[minmax(0,1fr),24rem] lg:items-end">
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Search results
            </p>
            <h1 class="max-w-[11ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              {{ keyword ? `Results for ${keyword}` : 'Search the archive' }}
            </h1>
            <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
              The updated prototype groups search into one featured result, related curators, collections, and a quieter image grid below.
            </p>
          </div>

          <SearchInput
            v-model="keyword"
            :is-expanded="searchExpanded"
            placeholder="Search by title, author, or topic"
            @expand="searchExpanded = true"
            @collapse="searchExpanded = false"
            @search="submitSearch"
          />
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

      <template v-else>
        <PrototypeContainer class="grid gap-10 lg:grid-cols-[minmax(0,1.45fr),22rem]">
          <button
            v-if="featuredResult"
            type="button"
            class="group overflow-hidden rounded-[2rem] border border-prototype-line bg-prototype-surface text-left shadow-[0_24px_60px_rgba(27,31,31,0.08)]"
            @click="openPost(featuredResult)"
          >
            <div class="aspect-[16/10] overflow-hidden bg-prototype-bg">
              <img :src="featuredResult.image" :alt="featuredResult.title" class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.04]">
            </div>
            <div class="grid gap-4 p-7">
              <div class="flex items-center justify-between gap-4">
                <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
                  Featured result
                </p>
                <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                  {{ resultsLabel }}
                </span>
              </div>
              <h2 class="max-w-[14ch] font-headline text-4xl tracking-[-0.04em] text-prototype-ink">
                {{ featuredResult.title }}
              </h2>
              <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
                {{ featuredResult.body }}
              </p>
              <div class="flex items-center gap-3 pt-1">
                <img :src="featuredResult.authorAvatar" :alt="featuredResult.author" class="h-9 w-9 rounded-full object-cover">
                <div class="grid gap-1">
                  <span class="text-sm font-semibold text-prototype-ink">{{ featuredResult.author }}</span>
                  <span class="text-[11px] uppercase tracking-[0.18em] text-prototype-muted">
                    {{ featuredResult.tags[0] ?? 'Curated archive' }}
                  </span>
                </div>
              </div>
            </div>
          </button>

          <div class="space-y-6">
            <section class="space-y-4 rounded-[2rem] border border-prototype-line bg-prototype-surface p-6">
              <div class="flex items-center gap-2">
                <ZenIcon name="group" :size="18" class="text-prototype-muted" />
                <h3 class="text-sm font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                  Related curators
                </h3>
              </div>
              <div class="space-y-4">
                <article
                  v-for="curator in relatedCurators"
                  :key="curator.id"
                  class="flex items-center gap-4 rounded-[1.5rem] bg-prototype-bg px-4 py-4"
                >
                  <img :src="curator.avatar" :alt="curator.name" class="h-14 w-14 rounded-full object-cover">
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-base font-semibold text-prototype-ink">{{ curator.name }}</p>
                    <p class="text-sm text-prototype-muted">{{ curator.descriptor }}</p>
                  </div>
                  <span class="text-[11px] font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                    {{ curator.resultCount }} hits
                  </span>
                </article>
              </div>
            </section>

            <section class="space-y-4 rounded-[2rem] border border-prototype-line bg-prototype-surface p-6">
              <div class="flex items-center gap-2">
                <ZenIcon name="grid_view" :size="18" class="text-prototype-muted" />
                <h3 class="text-sm font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                  Curated collections
                </h3>
              </div>
              <div class="grid gap-4">
                <article
                  v-for="collection in curatedCollections"
                  :key="collection.id"
                  class="grid grid-cols-[5rem,minmax(0,1fr)] gap-4 rounded-[1.5rem] bg-prototype-bg p-3"
                >
                  <div class="aspect-square overflow-hidden rounded-[1rem]">
                    <img :src="collection.cover" :alt="collection.title" class="h-full w-full object-cover">
                  </div>
                  <div class="grid content-center gap-1">
                    <p class="text-base font-semibold text-prototype-ink">{{ collection.title }}</p>
                    <p class="text-sm text-prototype-muted">{{ collection.meta }}</p>
                  </div>
                </article>
              </div>
            </section>
          </div>
        </PrototypeContainer>

        <PrototypeContainer class="space-y-5">
          <div class="flex items-center justify-between">
            <div>
              <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
                Search grid
              </p>
              <h2 class="mt-2 font-headline text-3xl tracking-[-0.03em] text-prototype-ink">
                More results
              </h2>
            </div>
            <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
              {{ galleryResults.length }} items
            </span>
          </div>

          <div class="grid gap-x-10 gap-y-12 md:grid-cols-2 xl:grid-cols-3">
            <button
              v-for="item in galleryResults"
              :key="item.id"
              type="button"
              class="group text-left transition hover:-translate-y-1.5"
              @click="openPost(item)"
            >
              <div class="mb-5 aspect-[4/5] overflow-hidden rounded-[1.2rem] bg-prototype-bg shadow-[0_22px_44px_rgba(27,31,31,0.08)]">
                <img :src="item.image" :alt="item.title" class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.04]">
              </div>
              <div class="space-y-3 px-1">
                <div class="flex items-start justify-between gap-4">
                  <h3 class="max-w-[16ch] font-headline text-[1.4rem] leading-8 tracking-[-0.03em] text-prototype-ink">
                    {{ item.title }}
                  </h3>
                  <span class="rounded-full bg-prototype-surface px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                    {{ item.tags[0] ?? 'Archive' }}
                  </span>
                </div>
                <p class="text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted">
                  {{ item.author }}
                </p>
              </div>
            </button>
          </div>
        </PrototypeContainer>
      </template>
    </article>
  </PrototypeShell>
</template>
