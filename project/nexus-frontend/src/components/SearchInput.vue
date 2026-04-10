<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import { fetchSuggest } from '@/api/search'
import SearchSuggestionPanel from '@/components/search/SearchSuggestionPanel.vue'

const props = withDefaults(
  defineProps<{
    isExpanded: boolean
    placeholder?: string
  }>(),
  {
    placeholder: '搜索帖子、作者、关键词'
  }
)

const emit = defineEmits<{
  (event: 'expand'): void
  (event: 'collapse'): void
  (event: 'search', keyword: string): void
}>()

const keyword = ref('')
const loading = ref(false)
const suggestions = ref<string[]>([])
const showPanel = ref(false)
const requestFailed = ref('')
let timer: number | null = null

const canSearch = computed(() => keyword.value.trim().length > 0)

const closePanel = () => {
  showPanel.value = false
  suggestions.value = []
  loading.value = false
  requestFailed.value = ''
}

const submit = () => {
  const value = keyword.value.trim()
  if (!value) return
  emit('search', value)
  closePanel()
}

const clear = () => {
  keyword.value = ''
  closePanel()
  emit('collapse')
}

const onFocus = () => {
  emit('expand')
  if (keyword.value.trim()) {
    showPanel.value = true
  }
}

const onBlur = () => {
  window.setTimeout(() => {
    closePanel()
    if (!keyword.value.trim()) {
      emit('collapse')
    }
  }, 140)
}

const selectSuggestion = (item: string) => {
  keyword.value = item
  submit()
}

const loadSuggestions = async () => {
  const query = keyword.value.trim()
  if (!query) {
    closePanel()
    return
  }

  loading.value = true
  requestFailed.value = ''
  showPanel.value = true

  try {
    const response = await fetchSuggest(query, 8)
    suggestions.value = Array.isArray(response.items) ? response.items : []
  } catch (error) {
    requestFailed.value = error instanceof Error ? error.message : '搜索建议加载失败'
    suggestions.value = []
  } finally {
    loading.value = false
  }
}

watch(keyword, () => {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    void loadSuggestions()
  }, 220)
})

watch(
  () => props.isExpanded,
  (expanded) => {
    if (!expanded) {
      closePanel()
    }
  }
)

onBeforeUnmount(() => {
  if (timer) window.clearTimeout(timer)
})
</script>

<template>
  <div class="relative w-full">
    <label
      class="grid min-h-[56px] w-full grid-cols-[20px,minmax(0,1fr),auto,auto] items-center gap-3 rounded-full border border-outline-variant/12 bg-surface-container-lowest/85 px-4 py-1.5 shadow-soft transition"
      :class="isExpanded ? 'bg-white/90' : ''"
      aria-label="搜索内容"
    >
      <ZenIcon name="search" :size="18" class="text-on-surface-variant" />

      <input
        v-model="keyword"
        type="search"
        inputmode="search"
        class="w-full min-w-0 border-none bg-transparent text-sm text-on-surface outline-none placeholder:text-outline-variant/80"
        :placeholder="placeholder"
        :aria-expanded="showPanel"
        aria-controls="search-suggestion-panel"
        @focus="onFocus"
        @blur="onBlur"
        @keydown.enter.prevent="submit"
      >

      <button
        v-if="keyword"
        type="button"
        class="grid h-9 w-9 place-items-center rounded-full text-on-surface-variant transition hover:bg-surface-container-low"
        aria-label="清空搜索"
        @mousedown.prevent
        @click="clear"
      >
        <ZenIcon name="close" :size="18" />
      </button>

      <ZenButton
        variant="primary"
        class="min-h-[42px] min-w-[88px] px-4 text-xs sm:text-sm"
        :disabled="!canSearch"
        @mousedown.prevent
        @click="submit"
      >
        搜索
      </ZenButton>
    </label>

    <div class="absolute inset-x-0 top-[calc(100%+10px)] z-50">
      <SearchSuggestionPanel
        panel-id="search-suggestion-panel"
        :open="showPanel"
        :loading="loading"
        :items="suggestions"
        :query="keyword"
        :error-message="requestFailed"
        @select="selectSuggestion"
        @retry="loadSuggestions"
      />
    </div>
  </div>
</template>
