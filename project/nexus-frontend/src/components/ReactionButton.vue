<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  isLiked: boolean
  count: number
}>()

const emit = defineEmits<{
  (event: 'toggle'): void
}>()

const label = computed(() => (props.isLiked ? '取消点赞' : '点赞'))

const onToggle = (event: Event) => {
  event.stopPropagation()
  emit('toggle')
}
</script>

<template>
  <button
    type="button"
    class="inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-white/70 bg-white/92 px-3 text-sm font-semibold text-on-surface shadow-[0_6px_16px_rgba(45,16,32,0.12)] transition hover:-translate-y-0.5"
    :class="isLiked ? 'text-primary' : ''"
    :aria-label="label"
    @click="onToggle"
  >
    <svg viewBox="0 0 24 24" class="h-[18px] w-[18px]" :fill="isLiked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2">
      <path d="M12 21.35 10.55 20C5.4 15.36 2 12.28 2 8.5A5.5 5.5 0 0 1 7.5 3c1.74 0 3.41.81 4.5 2.09A6 6 0 0 1 16.5 3 5.5 5.5 0 0 1 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35Z" />
    </svg>
    <span>{{ count }}</span>
  </button>
</template>
