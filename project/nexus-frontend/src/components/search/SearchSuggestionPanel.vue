<script setup lang="ts">
import { computed } from 'vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import { useRouteStateCopy } from '@/composables/useRouteStateCopy'

const props = withDefaults(
  defineProps<{
    open: boolean
    loading: boolean
    items: string[]
    query: string
    errorMessage?: string
    panelId?: string
  }>(),
  {
    errorMessage: '',
    panelId: 'search-suggestion-panel'
  }
)

const emit = defineEmits<{
  (event: 'select', value: string): void
  (event: 'retry'): void
}>()

const hasQuery = computed(() => props.query.trim().length > 0)
const hasError = computed(() => Boolean(props.errorMessage))
const hasItems = computed(() => props.items.length > 0)

const { getCopy } = useRouteStateCopy({
  loading: {
    title: '正在整理搜索建议',
    body: '稍等片刻，系统正在匹配更贴近的关键词。'
  },
  'request-failure': {
    title: '建议暂时不可用',
    body: '你仍然可以直接提交搜索，或者稍后重试。',
    actionLabel: '重新尝试'
  },
  'no-results': {
    title: '没有匹配的建议',
    body: '换一个更短或更明确的词，也许会更快找到内容。'
  }
})

const panelTitle = computed(() => {
  if (!hasQuery.value) return '开始搜索'
  return `关于 “${props.query.trim()}” 的即时建议`
})

const stateVariant = computed(() => {
  if (props.loading) return 'loading'
  if (hasError.value) return 'request-failure'
  return 'no-results'
})
</script>

<template>
  <section
    v-if="open"
    :id="panelId"
    class="tonal-panel grid gap-3 p-3"
    role="listbox"
    aria-label="搜索建议"
  >
    <header class="grid gap-1 px-1 pt-1">
      <p class="section-kicker">Search Notes</p>
      <h3 class="text-base font-semibold tracking-tight text-on-surface">{{ panelTitle }}</h3>
    </header>

    <div v-if="loading || hasError || (!hasItems && hasQuery)">
      <StatePanel
        :variant="stateVariant"
        :title="getCopy(stateVariant).title"
        :body="hasError ? errorMessage : getCopy(stateVariant).body"
        :action-label="getCopy(stateVariant).actionLabel"
        compact
        :surface="false"
        @action="emit('retry')"
      />
    </div>

    <ul v-else class="grid list-none gap-1 p-0">
      <li v-for="item in items" :key="item">
        <button
          type="button"
          class="grid min-h-[52px] w-full grid-cols-[18px,minmax(0,1fr)] items-center gap-3 rounded-2xl px-4 text-left text-sm text-on-surface transition hover:bg-surface-container-low"
          @mousedown.prevent
          @click="emit('select', item)"
        >
          <ZenIcon name="search" :size="18" class="text-on-surface-variant" />
          <span class="truncate">{{ item }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>
