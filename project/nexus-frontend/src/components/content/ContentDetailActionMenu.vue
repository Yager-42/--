<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    canDelete?: boolean
    busy?: boolean
  }>(),
  {
    canDelete: false,
    busy: false
  }
)

const emit = defineEmits<{
  (event: 'delete'): void
}>()
</script>

<template>
  <div v-if="props.canDelete" class="flex items-center">
    <button
      type="button"
      class="rounded-full border border-error/30 px-3 py-1.5 text-xs font-semibold uppercase tracking-[0.18em] text-error transition hover:border-error/50 disabled:cursor-not-allowed disabled:opacity-60"
      data-post-action="delete"
      :disabled="props.busy"
      @click="emit('delete')"
    >
      Delete post
    </button>
  </div>
</template>
