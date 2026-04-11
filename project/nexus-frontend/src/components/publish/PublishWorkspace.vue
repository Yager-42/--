<script setup lang="ts">
import ZenButton from '@/components/primitives/ZenButton.vue'

defineProps<{
  title: string
  content: string
  previews: string[]
  loading?: boolean
}>()

defineEmits<{
  (event: 'update:title', value: string): void
  (event: 'update:content', value: string): void
  (event: 'pick-file', eventValue: Event): void
  (event: 'remove-media', index: number): void
  (event: 'publish'): void
}>()
</script>

<template>
  <section class="grid gap-6">
    <label class="grid gap-2">
      <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">标题</span>
      <input
        :value="title"
        type="text"
        placeholder="给这次发布写一个标题（可选）"
        class="rounded-full border border-prototype-line bg-prototype-bg px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
        @input="$emit('update:title', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <label class="grid gap-2">
      <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">正文</span>
      <textarea
        :value="content"
        placeholder="分享你此刻真正想表达的内容。"
        class="min-h-[18rem] rounded-[1.75rem] border border-prototype-line bg-prototype-bg px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
        @input="$emit('update:content', ($event.target as HTMLTextAreaElement).value)"
      />
    </label>

    <div class="grid gap-3 md:grid-cols-3">
      <article
        v-for="(img, index) in previews"
        :key="img"
        class="relative aspect-square overflow-hidden rounded-[1.5rem] border border-prototype-line bg-prototype-surface"
      >
        <img :src="img" alt="preview" class="h-full w-full object-cover">
        <button
          type="button"
          class="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-full bg-black/55 text-base text-white"
          @click="$emit('remove-media', index)"
        >
          ×
        </button>
      </article>

      <label
        v-if="previews.length < 9"
        class="grid aspect-square cursor-pointer place-items-center gap-1 rounded-[1.5rem] border border-dashed border-prototype-line bg-prototype-bg text-prototype-muted"
      >
        <input hidden type="file" accept="image/*" @change="$emit('pick-file', $event)">
        <span class="text-3xl leading-none">+</span>
        <small class="text-xs font-semibold uppercase tracking-[0.18em]">添加图片</small>
      </label>
    </div>

    <ZenButton variant="primary" class="justify-self-end" :disabled="loading" @click="$emit('publish')">
      {{ loading ? '提交中...' : '发布内容' }}
    </ZenButton>
  </section>
</template>

