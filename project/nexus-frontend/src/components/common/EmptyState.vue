<script setup lang="ts">
defineProps<{
  title: string
  description: string
}>()
</script>

<template>
  <section class="rounded-[var(--nx-radius-card)] border border-dashed border-nx-border bg-nx-surface p-8 text-center">
    <h2 class="font-headline text-2xl font-semibold text-nx-text">{{ title }}</h2>
    <p class="mt-3 text-sm leading-6 text-nx-text-muted">{{ description }}</p>
  </section>
</template>
