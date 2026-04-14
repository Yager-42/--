<script setup lang="ts">
export interface HomeHeroCardModel {
  id: string
  title: string
  summary: string
  body: string
  author: string
  image: string
  category: string
  readTime: string
  likes: string
  tags: string[]
}

defineProps<{
  post: HomeHeroCardModel
}>()

defineEmits<{
  select: [post: HomeHeroCardModel]
}>()
</script>

<template>
  <article
    class="grid cursor-pointer gap-8 rounded-[2rem] border border-prototype-line bg-prototype-surface p-5 transition duration-200 hover:-translate-y-1 md:grid-cols-[minmax(0,1.1fr),minmax(20rem,0.9fr)] md:p-8"
    @click="$emit('select', post)"
  >
    <div class="min-h-[22rem] overflow-hidden rounded-[1.5rem] bg-prototype-bg">
      <img :src="post.image" :alt="post.title" class="h-full w-full object-cover">
    </div>

    <div class="grid content-end gap-5">
      <div class="flex flex-wrap items-center gap-3 text-[11px] font-semibold uppercase tracking-[0.22em] text-prototype-muted">
        <span>{{ post.category }}</span>
        <span class="h-1 w-1 rounded-full bg-prototype-line" />
        <span>{{ post.readTime }}</span>
      </div>
      <h1 class="font-headline text-[clamp(2.6rem,4vw,4.4rem)] leading-[0.94] tracking-[-0.05em] text-prototype-ink">
        {{ post.title }}
      </h1>
      <p class="max-w-[34rem] text-base leading-8 text-prototype-muted">
        {{ post.summary }}
      </p>
      <p class="text-sm font-semibold text-prototype-ink">
        {{ post.author }}
      </p>
    </div>
  </article>
</template>
