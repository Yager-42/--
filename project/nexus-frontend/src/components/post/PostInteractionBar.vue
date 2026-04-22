<script setup lang="ts">
defineProps<{
  liked?: boolean
  likeCountLabel: string
}>()

const emit = defineEmits<{
  (event: 'toggle-like'): void
}>()
</script>

<template>
  <div class="mt-5 flex flex-wrap items-center gap-3 border-t border-nx-border pt-5">
    <button
      data-test="like-button"
      type="button"
      class="inline-flex min-h-11 items-center justify-center rounded-full border px-4 text-sm font-medium transition"
      :class="liked ? 'border-nx-primary bg-blue-50 text-nx-primary' : 'border-nx-border text-nx-text'"
      @click="emit('toggle-like')"
    >
      {{ liked ? '已赞' : '点赞' }} · {{ likeCountLabel }}
    </button>
  </div>
</template>
