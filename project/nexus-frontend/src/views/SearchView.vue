<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import SearchResultTabs from '@/components/search/SearchResultTabs.vue'
import UserResultRow from '@/components/search/UserResultRow.vue'
import { searchContent, searchUsers, suggestKeywords } from '@/services/api/searchApi'
import type {
  SearchContentCardViewModel,
  SearchResultTab,
  SearchUserViewModel
} from '@/types/search'

const route = useRoute()
const router = useRouter()

const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const activeTab = ref<SearchResultTab>(route.query.tab === 'users' ? 'users' : 'content')
const contentResults = ref<SearchContentCardViewModel[]>([])
const userResults = ref<SearchUserViewModel[]>([])
const suggestions = ref<string[]>([])
const isLoading = ref(false)

const showSuggestions = computed(() => Boolean(query.value.trim()) && suggestions.value.length > 0)

watch(
  () => route.query,
  (nextQuery) => {
    query.value = typeof nextQuery.q === 'string' ? nextQuery.q : ''
    activeTab.value = nextQuery.tab === 'users' ? 'users' : 'content'
  }
)

watch([query, activeTab], async () => {
  await loadResults()
})

watch(
  query,
  async (value) => {
    if (!value.trim()) {
      suggestions.value = []
      return
    }

    const response = await suggestKeywords(value.trim())
    suggestions.value = response.items
  },
  { immediate: true }
)

onMounted(async () => {
  await loadResults()
})

function updateRoute(next: { q?: string; tab?: SearchResultTab }) {
  router.replace({
    query: {
      ...route.query,
      q: next.q ?? query.value,
      tab: next.tab ?? activeTab.value
    }
  })
}

function handleQueryInput(event: Event) {
  const target = event.target as HTMLInputElement
  query.value = target.value
  updateRoute({ q: target.value })
}

function handleTabChange(tab: SearchResultTab) {
  activeTab.value = tab
  updateRoute({ tab })
}

function applySuggestion(keyword: string) {
  query.value = keyword
  updateRoute({ q: keyword })
}

async function loadResults() {
  if (!query.value.trim()) {
    contentResults.value = []
    userResults.value = []
    return
  }

  isLoading.value = true

  try {
    if (activeTab.value === 'content') {
      const response = await searchContent(query.value.trim())
      contentResults.value = response.items
      return
    }

    userResults.value = await searchUsers(query.value.trim())
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <section class="space-y-5">
    <header class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <div class="space-y-4">
        <div>
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Discovery</p>
          <h1 class="mt-2 font-headline text-3xl font-semibold text-nx-text">搜索内容与用户</h1>
        </div>

        <div class="space-y-3">
          <input
            data-test="search-input"
            :value="query"
            type="search"
            placeholder="输入关键词、作者或主题"
            class="h-12 w-full rounded-full border border-nx-border bg-white px-5 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            @input="handleQueryInput"
          />

          <div v-if="showSuggestions" class="flex flex-wrap gap-2">
            <button
              v-for="item in suggestions"
              :key="item"
              data-test="suggestion-item"
              type="button"
              class="min-h-11 rounded-full border border-nx-border bg-white px-4 text-sm font-medium text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
              @click="applySuggestion(item)"
            >
              {{ item }}
            </button>
          </div>
        </div>

        <SearchResultTabs :active-tab="activeTab" @change="handleTabChange" />
      </div>
    </header>

    <div v-if="activeTab === 'content'" class="space-y-4">
      <LoadingSkeleton v-if="isLoading" />

      <RouterLink
        v-for="item in contentResults"
        :key="item.id"
        :data-test="`search-content-link-${item.id}`"
        :to="`/post/${item.id}`"
        class="block rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)] transition hover:-translate-y-0.5 hover:border-nx-primary"
      >
        <article>
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0">
              <h2 class="font-headline text-2xl font-semibold text-nx-text">{{ item.title }}</h2>
              <p class="mt-3 text-sm leading-6 text-nx-text-muted">{{ item.description }}</p>
            </div>
            <span class="rounded-full bg-nx-surface-muted px-3 py-1 text-xs font-medium text-nx-text-muted">
              {{ item.likeCountLabel }} 喜欢
            </span>
          </div>

          <p v-if="item.authorName" class="mt-4 text-sm font-medium text-nx-text-muted">
            作者：{{ item.authorName }}
          </p>

          <div class="mt-4 flex flex-wrap gap-2">
            <span
              v-for="tag in item.tags"
              :key="tag"
              class="rounded-full border border-nx-border px-3 py-1 text-xs font-medium text-nx-text-muted"
            >
              {{ tag }}
            </span>
          </div>
        </article>
      </RouterLink>

      <EmptyState
        v-if="!contentResults.length"
        title="还没有搜索结果"
        description="试试更具体的关键词，或者切换到用户页签。"
      />
    </div>

    <div v-else class="space-y-4">
      <LoadingSkeleton v-if="isLoading" />

      <UserResultRow v-for="user in userResults" :key="user.id" :user="user" />

      <EmptyState
        v-if="!userResults.length"
        title="没有匹配的用户"
        description="换个昵称、主题词或更短的关键词再试一次。"
      />
    </div>
  </section>
</template>
