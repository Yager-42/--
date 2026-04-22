<script setup lang="ts">
defineProps<{
  message: string
  tone?: 'error' | 'info'
}>()
</script>

<template>
  <p
    class="rounded-2xl border px-4 py-3 text-sm"
    :class="
      tone === 'error'
        ? 'border-red-200 bg-red-50 text-red-700'
        : 'border-nx-border bg-nx-surface-muted text-nx-text-muted'
    "
  >
    {{ message }}
  </p>
</template>
