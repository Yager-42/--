<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  body?: string
}>()
</script>

<template>
  <header class="space-y-3">
    <p
      v-if="eyebrow"
      class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted"
    >
      {{ eyebrow }}
    </p>
    <h2 class="font-headline text-4xl tracking-[-0.03em] text-prototype-ink md:text-5xl">
      {{ title }}
    </h2>
    <p v-if="body" class="max-w-reading text-base leading-7 text-prototype-muted">
      {{ body }}
    </p>
    <slot />
  </header>
</template>
