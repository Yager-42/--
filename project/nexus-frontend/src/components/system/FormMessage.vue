<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    tone?: 'success' | 'error' | 'info'
    title?: string
    message: string
  }>(),
  {
    tone: 'info',
    title: ''
  }
)

const iconPath = computed(() => ({
  success: 'M10.4 16.6 6.8 13l-1.4 1.4 5 5 8.8-8.8-1.4-1.4-7.4 7.4Z',
  error: 'M12 2.8a9.2 9.2 0 1 0 0 18.4 9.2 9.2 0 0 0 0-18.4Zm3.1 11.7-1.4 1.4L12 13.4l-1.7 2.5-1.4-1.4 1.7-2.5-1.7-2.5 1.4-1.4 1.7 2.5 1.7-2.5 1.4 1.4-1.7 2.5 1.7 2.5Z',
  info: 'M11 10h2v6h-2v-6Zm0-3h2v2h-2V7Z'
}[props.tone]))

const role = computed(() => (props.tone === 'error' ? 'alert' : 'status'))
</script>

<template>
  <aside
    class="inline-flex items-start gap-3 rounded-2xl border px-4 py-3 text-sm"
    :class="tone === 'success'
      ? 'border-[rgba(61,97,73,0.22)] bg-[rgba(244,251,246,0.92)] text-[rgb(61,97,73)]'
      : tone === 'error'
        ? 'border-[rgba(158,76,62,0.22)] bg-[rgba(253,246,243,0.92)] text-[rgb(158,76,62)]'
        : 'border-outline-variant/20 bg-white/72 text-[rgb(82,102,123)]'"
    :role="role"
  >
    <span class="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true">
      <svg viewBox="0 0 24 24" class="h-full w-full">
        <path :d="iconPath" fill="currentColor" />
      </svg>
    </span>

    <div class="grid gap-0.5">
      <strong v-if="title" class="text-sm font-semibold text-on-surface">{{ title }}</strong>
      <span class="leading-6">{{ message }}</span>
    </div>
  </aside>
</template>
