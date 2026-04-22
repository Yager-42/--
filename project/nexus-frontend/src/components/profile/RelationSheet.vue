<script setup lang="ts">
import type { RelationUserViewModel } from '@/types/profile'

defineProps<{
  title: string
  items: RelationUserViewModel[]
}>()

const emit = defineEmits<{
  (event: 'close'): void
}>()
</script>

<template>
  <div class="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/35 p-4">
    <section class="w-full max-w-xl rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <header class="flex items-center justify-between gap-4">
        <div>
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Relations</p>
          <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">{{ title }}</h2>
        </div>

        <button
          data-test="close-relations"
          type="button"
          class="min-h-11 rounded-full border border-nx-border px-4 text-sm font-medium text-nx-text"
          @click="emit('close')"
        >
          关闭
        </button>
      </header>

      <div class="mt-5 space-y-3">
        <article
          v-for="item in items"
          :key="item.id"
          class="rounded-3xl border border-nx-border bg-nx-surface-muted px-4 py-4"
        >
          <p class="text-sm font-semibold text-nx-text">{{ item.nickname }}</p>
          <p class="mt-1 text-sm text-nx-text-muted">{{ item.followTimeLabel }}</p>
        </article>
      </div>
    </section>
  </div>
</template>
