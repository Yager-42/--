<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { fetchSuggest } from '@/api/search'

const props = defineProps<{
  isExpanded: boolean
}>()

const emit = defineEmits<{
  (event: 'expand'): void
  (event: 'collapse'): void
  (event: 'search', keyword: string): void
}>()

const keyword = ref('')
const loading = ref(false)
const suggestions = ref<string[]>([])
const showPanel = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

const closePanel = () => {
  showPanel.value = false
  suggestions.value = []
}

const submit = () => {
  const value = keyword.value.trim()
  if (!value) return
  emit('search', value)
  closePanel()
}

const onFocus = () => {
  emit('expand')
  if (suggestions.value.length > 0) {
    showPanel.value = true
  }
}

const onBlur = () => {
  setTimeout(() => {
    closePanel()
    if (!keyword.value.trim()) {
      emit('collapse')
    }
  }, 120)
}

const choose = (item: string) => {
  keyword.value = item
  submit()
}

const searchSuggest = async () => {
  const q = keyword.value.trim()
  if (!q) {
    closePanel()
    return
  }

  loading.value = true
  try {
    const res = await fetchSuggest(q)
    suggestions.value = Array.isArray(res.items) ? res.items : []
    showPanel.value = suggestions.value.length > 0
  } catch (error) {
    console.error('load suggestions failed', error)
    closePanel()
  } finally {
    loading.value = false
  }
}

watch(keyword, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(searchSuggest, 220)
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

watch(
  () => props.isExpanded,
  (expanded) => {
    if (!expanded) {
      closePanel()
    }
  }
)
</script>

<template>
  <div class="search-box" :class="{ expanded: isExpanded }">
    <label class="input-wrap" aria-label="搜索内容">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M11 4a7 7 0 1 0 4.6 12.3L20 20.7 21.3 19l-4.4-4.4A7 7 0 0 0 11 4Z" fill="currentColor" />
      </svg>
      <input
        v-model="keyword"
        type="search"
        inputmode="search"
        placeholder="搜索帖子、作者、关键词"
        @focus="onFocus"
        @blur="onBlur"
        @keydown.enter.prevent="submit"
      >
    </label>

    <button
      v-if="isExpanded"
      class="go-btn"
      type="button"
      :disabled="!keyword.trim()"
      @mousedown.prevent
      @click="submit"
    >
      搜索
    </button>

    <div v-if="showPanel" class="suggestion-panel" role="listbox">
      <div v-if="loading" class="suggestion-item muted">正在加载建议...</div>
      <button
        v-for="item in suggestions"
        v-else
        :key="item"
        type="button"
        class="suggestion-item"
        @mousedown.prevent
        @click="choose(item)"
      >
        {{ item }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.search-box {
  position: relative;
  width: 240px;
  transition: width 220ms ease;
}

.search-box.expanded {
  width: min(640px, 100%);
}

.input-wrap {
  min-height: 44px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--border-soft);
  display: grid;
  grid-template-columns: 20px 1fr;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
}

.input-wrap svg {
  width: 18px;
  height: 18px;
  color: var(--text-secondary);
}

input {
  border: none;
  outline: none;
  background: transparent;
  width: 100%;
  color: var(--text-primary);
}

input::placeholder {
  color: var(--text-muted);
}

.go-btn {
  position: absolute;
  right: 8px;
  top: 6px;
  height: 32px;
  border-radius: 999px;
  padding: 0 12px;
  background: var(--brand-primary);
  color: #fff;
  font-size: 0.82rem;
  font-weight: 700;
}

.go-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.suggestion-panel {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 8px);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  box-shadow: var(--shadow-soft);
  overflow: hidden;
  z-index: 40;
}

.suggestion-item {
  width: 100%;
  min-height: 44px;
  text-align: left;
  padding: 0 14px;
  border-bottom: 1px solid #fbe5ea;
  background: transparent;
}

.suggestion-item:last-child {
  border-bottom: none;
}

.suggestion-item:hover {
  background: #fff4f7;
}

.muted {
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .search-box,
  .search-box.expanded {
    width: 100%;
  }
}
</style>
