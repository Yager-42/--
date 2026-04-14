<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    canDelete?: boolean
    canPin?: boolean
    busy?: boolean
  }>(),
  {
    canDelete: false,
    canPin: false,
    busy: false
  }
)

const emit = defineEmits<{
  (event: 'delete'): void
  (event: 'pin'): void
}>()
</script>

<template>
  <div
    v-if="props.canDelete || props.canPin"
    class="flex flex-wrap items-center gap-3"
  >
    <button
      v-if="props.canPin"
      type="button"
      class="text-xs font-semibold uppercase tracking-[0.2em] text-prototype-muted transition hover:text-prototype-ink disabled:cursor-not-allowed disabled:opacity-60"
      data-comment-action="pin"
      :disabled="props.busy"
      @click="emit('pin')"
    >
      Pin
    </button>

    <button
      v-if="props.canDelete"
      type="button"
      class="text-xs font-semibold uppercase tracking-[0.2em] text-error transition hover:opacity-80 disabled:cursor-not-allowed disabled:opacity-60"
      data-comment-action="delete"
      :disabled="props.busy"
      @click="emit('delete')"
    >
      Delete
    </button>
  </div>
</template>
