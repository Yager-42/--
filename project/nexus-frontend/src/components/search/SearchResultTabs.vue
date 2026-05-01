<script setup lang="ts">
import type { SearchResultTab } from '@/types/search'

defineProps<{
  activeTab: SearchResultTab
}>()

const emit = defineEmits<{
  (event: 'change', value: SearchResultTab): void
}>()
</script>

<template>
  <div class="inline-flex rounded-full border border-nx-border bg-nx-surface-muted p-1">
    <button
      data-test="search-tab-content"
      type="button"
      class="min-h-11 rounded-full px-4 text-sm font-medium transition"
      :class="activeTab === 'content' ? 'bg-white text-nx-text shadow-sm' : 'text-nx-text-muted'"
      :aria-pressed="activeTab === 'content'"
      @click="emit('change', 'content')"
    >
      内容
    </button>
    <button
      data-test="search-tab-users"
      type="button"
      class="min-h-11 rounded-full px-4 text-sm font-medium transition"
      :class="activeTab === 'users' ? 'bg-white text-nx-text shadow-sm' : 'text-nx-text-muted'"
      :aria-pressed="activeTab === 'users'"
      @click="emit('change', 'users')"
    >
      用户
    </button>
  </div>
</template>
