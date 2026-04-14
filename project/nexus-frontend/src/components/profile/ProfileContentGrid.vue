<script setup lang="ts">
defineProps<{
  items: Array<{
    id: string
    title: string
    subtitle: string
  }>
}>()
</script>

<template>
  <section class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
    <article
      v-for="item in items"
      :key="item.id"
      class="grid min-h-[12rem] content-end gap-3 rounded-[1.75rem] border border-prototype-line bg-prototype-bg p-5"
    >
      <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-prototype-muted">
        Archive Note
      </p>
      <h3 class="font-headline text-2xl tracking-[-0.03em] text-prototype-ink">{{ item.title }}</h3>
      <p class="text-sm leading-7 text-prototype-muted">{{ item.subtitle }}</p>
    </article>
  </section>
</template>

