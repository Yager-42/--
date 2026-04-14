<script setup lang="ts">
defineProps<{
  title?: string
  items: Array<{
    id: string
    title: string
    subtitle?: string
  }>
}>()

defineEmits<{
  (event: 'select', id: string): void
}>()
</script>

<template>
  <section class="grid gap-4">
    <div class="grid gap-1.5">
      <p class="section-kicker">Continue Reading</p>
      <h2 class="text-2xl font-bold tracking-tight text-on-surface">
        {{ title || '继续浏览相关内容' }}
      </h2>
    </div>

    <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        class="grid min-h-[160px] content-end gap-2 rounded-3xl border border-outline-variant/10 bg-white/80 p-5 text-left shadow-soft transition hover:-translate-y-0.5 hover:shadow-float"
        @click="$emit('select', item.id)"
      >
        <span class="text-lg font-bold tracking-tight text-on-surface">{{ item.title }}</span>
        <span v-if="item.subtitle" class="text-sm leading-7 text-on-surface-variant">{{ item.subtitle }}</span>
      </button>
    </div>
  </section>
</template>
