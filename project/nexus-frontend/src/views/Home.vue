<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FeedCardViewModel } from '@/api/feed'
import { useFeedStore } from '@/store/feed'
import HomeHeroCard from '@/components/home/HomeHeroCard.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeReadingColumn from '@/components/prototype/PrototypeReadingColumn.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

interface EditorialSlot {
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

const router = useRouter()
const feedStore = useFeedStore()
const searchQuery = ref('')

const fallbackFeatured: EditorialSlot = {
  id: 'editorial-fallback',
  title: 'The Architecture of Solitude in Modern Spaces',
  summary: 'In an era defined by constant connectivity, the luxury of silence becomes a design discipline.',
  body: 'True minimalism is a dialogue between light and shadow. When we strip away the superfluous, the remaining elements speak with clarity and emotional precision.',
  author: 'Julian Vesper',
  image:
    'https://lh3.googleusercontent.com/aida-public/AB6AXuBTCs88xEa7JebkrFHDPxQD_O16R7dw-yR3WocINuzxIZDAsZh1cWTj1X4MN2-I9B-e6YcUFs0cOk5olVTxfYLUMZ602ZSmWrHc7drPrZD_fFiRH0X2ZLlJCfcEJYcz5xaScGxWcdxRoYo4UxrsMyyA2zyAFZeVe5X4UvLTQG-6FB3BK365BX6GRJD8McMKQAyNaiM8JKH1FWLyWYX83151fS0QPj4Ur0nvGUnNG_NJT8rvjrXzbu9Qog6GbUQrOKGwodpg8yNzQwwi',
  category: 'Photography',
  readTime: '8 min read',
  likes: '2.4k appreciations',
  tags: ['Architecture', 'Mindfulness', 'Design Philosophy', 'Solitude']
}

const fallbackCards: EditorialSlot[] = [
  {
    id: 'fallback-1',
    title: 'Light As Narrative Material',
    summary: 'When the room softens, attention sharpens.',
    body: 'A quiet room is not empty. It is edited. Light becomes structure, and silence becomes a framing device.',
    author: 'Nadia Rose',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuCnXjaf-neWdc6YzD26CjQraZPjcJfuXIRTvS3ZjWgfk9FMyXwz1T9DXgOxekghVWela2eOd5thFPLoJJvBUIM1YDGMasD6Pe0lsLHYdmmnzg_i7Ht6qw2SAghBlVavP4t4kksBHZtfkox6xZJ_wdyDNO0-suzurZhAV5_2G-oc-MwrSAM7DcMsiv8mMWpB0UnfHOtP3fAwu3e1uls4xQqWZES6VPue1ucfReRgPmaCzaJ9XblG2lGyrIjnj9e71Ku2UgajUyPQq-xZ',
    category: 'Interior',
    readTime: '5 min read',
    likes: '1.1k appreciations',
    tags: ['Light', 'Quiet']
  },
  {
    id: 'fallback-2',
    title: 'The Editorial Value of Empty Space',
    summary: 'Negative space is pacing, not absence.',
    body: 'Spacing is not leftover air. It is the rhythm that lets each block of meaning arrive with intention.',
    author: 'Elena Soros',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuAxffDxv86_jfGu5oD6N888ak6V51-hdjz1-ivB7uHNgEUK7ml7u7YyAViiVeMJaTbEcV2QtB2Gjh_Syy2CeOaHjbq9OmUYkoiJuUaPNBMVzD_xdYtp_0QrCrWCi3KNgEiQMdEyCZ97EujeVfht4ITCEa4uzZEngkYp0fr5a3CYG5XT1iQCKZkpgC2xAlWlNOZHOXMlRl7QclOUZxWUfYa4yotApk451_MqN6Ss0O9XV0ZsVo8pB3akycBDqwyg3zWMCypMZ5AEqGKo',
    category: 'Minimalism',
    readTime: '6 min read',
    likes: '980 appreciations',
    tags: ['Rhythm', 'Layout']
  },
  {
    id: 'fallback-3',
    title: 'Rooms That Refuse To Shout',
    summary: 'Soft materials hold attention longer than loud surfaces.',
    body: 'Editorial calm is built with restraint: linen textures, low-contrast edges, and surfaces that never demand performance.',
    author: 'Marcus Thorne',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuCtz72u02xqM9fCGeRe4fpcPahXntM8bjZy1WrYyKcOPKV6yW5glsoCKf5UYsZzVlXFXA_lLmnnwLw3Zei6fy4MJNTY3Wcx8TFRLHVW8WYGqI_PsTCEingPkEUx9tY-MPqOWkA_vt9F7o2BorpbhuF75AOalEZZyA0Fd7mf76KbzsZjLTzRtZ3TsMuyM-_xHU2GDYieWmqNIw2bxxEMwCHjZR2C87xyBiPke98hf-nHVb9OvXaXXpN4EINegLco1Vry245klBgDOiXS',
    category: 'Spaces',
    readTime: '4 min read',
    likes: '760 appreciations',
    tags: ['Material', 'Calm']
  }
]

const toEditorialSlot = (post: FeedCardViewModel, index: number): EditorialSlot => ({
  id: post.postId,
  title: post.title,
  summary: post.body.trim().slice(0, 120) || '进入详情继续浏览这篇内容。',
  body: post.body.trim() || '进入详情继续浏览这篇内容。',
  author: post.author,
  image: post.image,
  category: ['Photography', 'Editorial', 'Reflection', 'Archive'][index % 4],
  readTime: `${Math.max(3, Math.ceil((post.body.length || 120) / 90))} min read`,
  likes: `${post.reactionCount} appreciations`,
  tags: ['Editorial', 'Curated', 'Narrative', 'Gallery']
})

onMounted(() => {
  if (feedStore.posts.length === 0) {
    void feedStore.fetchNextPage()
  }
})

const editorialPosts = computed(() => feedStore.posts.map(toEditorialSlot))
const featured = computed(() => editorialPosts.value[0] ?? fallbackFeatured)
const supporting = computed(() => {
  const items = editorialPosts.value.slice(1, 4)
  return items.length > 0 ? items : fallbackCards
})
const detailImages = computed(() => supporting.value.slice(0, 2))
const bodyParagraphs = computed(() => {
  const featuredParagraphs = featured.value.body
    .split(/(?<=[。！？.!?])\s*/)
    .map((item) => item.trim())
    .filter(Boolean)

  const merged = [
    featured.value.summary,
    ...featuredParagraphs,
    ...supporting.value.map((item) => item.summary),
    ...supporting.value.map((item) => item.body)
  ]

  return merged.slice(0, 4)
})
const reflectionItems = computed(() =>
  supporting.value.map((item, index) => ({
    ...item,
    timeLabel: ['2h ago', '5h ago', 'Yesterday'][index] ?? 'Recently',
    likesLabel: [42, 18, 9][index] ?? 12
  }))
)
const inlineStatus = computed(() => {
  if (feedStore.error) return '实时内容暂时不可用，当前展示的是原型化回退布局。'
  if (feedStore.loading && feedStore.posts.length === 0) return '正在把实时内容注入到当前 gallery 布局中。'
  return ''
})
const openPost = (slot: EditorialSlot) => {
  if (slot.id.startsWith('fallback-') || slot.id === fallbackFeatured.id) return
  void router.push(`/content/${slot.id}`)
}

const goSearch = () => {
  const keyword = searchQuery.value.trim()
  void router.push(keyword ? { path: '/search', query: { q: keyword } } : '/search')
}

const navTo = (path: string) => {
  void router.push(path)
}
</script>

<template>
  <PrototypeShell>
    <article data-prototype-home class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="grid gap-8 lg:grid-cols-[minmax(0,1fr),20rem] lg:items-end">
          <div class="space-y-4">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Curated Gallery
            </p>
            <h1 class="max-w-[12ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              A quieter entrance into the Nexus archive.
            </h1>
            <p class="max-w-2xl text-base leading-8 text-prototype-muted">
              Browse the current feed through a prototype-aligned desktop layout that favors reading rhythm over showcase chrome.
            </p>
          </div>

          <label class="flex items-center gap-3 rounded-full border border-prototype-line bg-prototype-surface px-5 py-3">
            <ZenIcon name="search" :size="18" class="text-prototype-muted" />
            <input
              v-model="searchQuery"
              type="search"
              class="w-full border-none bg-transparent text-sm text-prototype-ink outline-none placeholder:text-prototype-muted/70"
              placeholder="Search sanctuary..."
              @keydown.enter.prevent="goSearch"
            >
          </label>
        </div>

        <p v-if="inlineStatus" class="max-w-2xl text-sm leading-7 text-prototype-muted">
          {{ inlineStatus }}
        </p>
      </PrototypeContainer>

      <PrototypeContainer width="content">
        <HomeHeroCard :post="featured" @select="openPost" />
      </PrototypeContainer>

      <PrototypeContainer class="grid gap-6 md:grid-cols-3">
        <button
          v-for="card in supporting"
          :key="card.id"
          type="button"
          class="group rounded-[1.75rem] border border-prototype-line bg-prototype-surface p-4 text-left transition hover:-translate-y-1"
          @click="openPost(card)"
        >
          <div class="mb-4 aspect-[4/3] overflow-hidden rounded-[1.25rem] bg-prototype-bg">
            <img :src="card.image" :alt="card.title" class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.03]">
          </div>
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.2em] text-prototype-muted">
              {{ card.category }}
            </p>
            <h2 class="font-headline text-2xl tracking-[-0.03em] text-prototype-ink">
              {{ card.title }}
            </h2>
            <p class="text-sm leading-7 text-prototype-muted">
              {{ card.summary }}
            </p>
            <p class="text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted">
              {{ card.author }}
            </p>
          </div>
        </button>
      </PrototypeContainer>

      <PrototypeContainer width="content" v-if="detailImages.length === 2">
        <div class="grid gap-4 md:grid-cols-[minmax(0,1.7fr),minmax(0,1fr)]">
          <button
            type="button"
            class="aspect-[16/11] overflow-hidden rounded-[1.75rem] bg-prototype-surface"
            @click="openPost(detailImages[0])"
          >
            <img :src="detailImages[0].image" :alt="detailImages[0].title" class="h-full w-full object-cover">
          </button>
          <button
            type="button"
            class="aspect-[4/5] overflow-hidden rounded-[1.75rem] bg-prototype-surface"
            @click="openPost(detailImages[1])"
          >
            <img :src="detailImages[1].image" :alt="detailImages[1].title" class="h-full w-full object-cover">
          </button>
        </div>
      </PrototypeContainer>

      <PrototypeReadingColumn>
        <div class="space-y-6">
          <p
            v-for="(paragraph, index) in bodyParagraphs"
            :key="`${featured.id}-paragraph-${index}`"
            :class="index === 0 ? 'text-xl italic leading-9 text-prototype-ink' : 'text-base leading-8 text-prototype-muted'"
          >
            {{ paragraph }}
          </p>
        </div>

        <div class="flex flex-wrap gap-2 pt-2">
          <span
            v-for="tag in featured.tags"
            :key="tag"
            class="rounded-full border border-prototype-line px-4 py-2 text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted"
          >
            {{ tag }}
          </span>
        </div>

        <div class="border-t border-prototype-line pt-12">
          <div class="mb-8 flex items-center justify-between">
            <h2 class="font-headline text-3xl tracking-[-0.03em] text-prototype-ink">Shared Reflections</h2>
            <button
              type="button"
              class="flex items-center gap-2 text-sm font-semibold text-prototype-accent transition hover:text-prototype-ink"
              @click="navTo('/search')"
            >
              Newest First
              <ZenIcon name="expand_more" :size="18" />
            </button>
          </div>

          <div class="space-y-8">
            <button
              v-for="item in reflectionItems"
              :key="`${item.id}-reflection`"
              type="button"
              class="flex w-full gap-4 border-b border-prototype-line pb-8 text-left last:border-b-0 last:pb-0"
              @click="openPost(item)"
            >
              <div class="h-10 w-10 shrink-0 overflow-hidden rounded-full bg-prototype-bg">
                <img :src="item.image" :alt="item.author" class="h-full w-full object-cover">
              </div>
              <div class="space-y-2">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-semibold text-prototype-ink">{{ item.author }}</span>
                  <span class="text-[10px] font-semibold uppercase tracking-[0.2em] text-prototype-muted">{{ item.timeLabel }}</span>
                </div>
                <p class="text-sm leading-7 text-prototype-muted">
                  {{ item.summary }} {{ item.body }}
                </p>
                <div class="flex items-center gap-4 pt-1">
                  <span class="flex items-center gap-1 text-xs font-semibold text-prototype-accent">
                    <ZenIcon name="thumb_up" :size="14" />
                    {{ item.likesLabel }}
                  </span>
                  <span class="text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted">Reply</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </PrototypeReadingColumn>
    </article>
  </PrototypeShell>
</template>
