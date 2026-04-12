<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FeedCardViewModel } from '@/api/feed'
import SearchInput from '@/components/SearchInput.vue'
import { useFeedStore, type FeedViewMode } from '@/store/feed'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'

interface FeedPreviewCard {
  id: string
  authorId?: string
  title: string
  author: string
  image: string
  category: string
}

const router = useRouter()
const feedStore = useFeedStore()
const searchQuery = ref('')
const searchExpanded = ref(false)
const feedTabs: Array<{
  label: 'Following' | 'Recommended' | 'Trending'
  value: FeedViewMode
  eyebrow: string
  heading: string
  description: string
}> = [
  {
    label: 'Following',
    value: 'FOLLOWING',
    eyebrow: 'Following feed',
    heading: 'A quieter entrance into the Nexus archive.',
    description: 'Posts from people you already follow, arranged as a calm gallery instead of an article-first homepage.'
  },
  {
    label: 'Recommended',
    value: 'RECOMMENDED',
    eyebrow: 'Recommended feed',
    heading: 'Signals worth opening, without breaking the grid.',
    description: 'Suggested posts keep the same centered rhythm and card size, so discovery feels curated instead of noisy.'
  },
  {
    label: 'Trending',
    value: 'TRENDING',
    eyebrow: 'Trending feed',
    heading: 'Momentum, but still framed with restraint.',
    description: 'The most active posts surface first, while the layout stays uniform and avoids inline detail expansion.'
  }
]

const fallbackCards: FeedPreviewCard[] = [
  {
    id: 'fallback-1',
    title: 'Light As Narrative Material',
    author: 'Nadia Rose',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuCnXjaf-neWdc6YzD26CjQraZPjcJfuXIRTvS3ZjWgfk9FMyXwz1T9DXgOxekghVWela2eOd5thFPLoJJvBUIM1YDGMasD6Pe0lsLHYdmmnzg_i7Ht6qw2SAghBlVavP4t4kksBHZtfkox6xZJ_wdyDNO0-suzurZhAV5_2G-oc-MwrSAM7DcMsiv8mMWpB0UnfHOtP3fAwu3e1uls4xQqWZES6VPue1ucfReRgPmaCzaJ9XblG2lGyrIjnj9e71Ku2UgajUyPQq-xZ',
    category: 'Interior'
  },
  {
    id: 'fallback-2',
    title: 'The Editorial Value of Empty Space',
    author: 'Elena Soros',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuAxffDxv86_jfGu5oD6N888ak6V51-hdjz1-ivB7uHNgEUK7ml7u7YyAViiVeMJaTbEcV2QtB2Gjh_Syy2CeOaHjbq9OmUYkoiJuUaPNBMVzD_xdYtp_0QrCrWCi3KNgEiQMdEyCZ97EujeVfht4ITCEa4uzZEngkYp0fr5a3CYG5XT1iQCKZkpgC2xAlWlNOZHOXMlRl7QclOUZxWUfYa4yotApk451_MqN6Ss0O9XV0ZsVo8pB3akycBDqwyg3zWMCypMZ5AEqGKo',
    category: 'Minimalism'
  },
  {
    id: 'fallback-3',
    title: 'Rooms That Refuse To Shout',
    author: 'Marcus Thorne',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuCtz72u02xqM9fCGeRe4fpcPahXntM8bjZy1WrYyKcOPKV6yW5glsoCKf5UYsZzVlXFXA_lLmnnwLw3Zei6fy4MJNTY3Wcx8TFRLHVW8WYGqI_PsTCEingPkEUx9tY-MPqOWkA_vt9F7o2BorpbhuF75AOalEZZyA0Fd7mf76KbzsZjLTzRtZ3TsMuyM-_xHU2GDYieWmqNIw2bxxEMwCHjZR2C87xyBiPke98hf-nHVb9OvXaXXpN4EINegLco1Vry245klBgDOiXS',
    category: 'Spaces'
  },
  {
    id: 'fallback-4',
    title: 'A Pace That Readers Can Hold',
    author: 'Mina Gray',
    image: 'https://images.unsplash.com/photo-1519710164239-da123dc03ef4?w=1200&q=80',
    category: 'Editorial'
  },
  {
    id: 'fallback-5',
    title: 'Calm Interfaces Need Strong Grids',
    author: 'Jon Park',
    image: 'https://images.unsplash.com/photo-1493666438817-866a91353ca9?w=1200&q=80',
    category: 'Systems'
  },
  {
    id: 'fallback-6',
    title: 'Neutral Color, Clear Hierarchy',
    author: 'Reese Lin',
    image: 'https://images.unsplash.com/photo-1505691938895-1758d7feb511?w=1200&q=80',
    category: 'Visual'
  }
]

const toFeedPreview = (post: FeedCardViewModel, index: number): FeedPreviewCard => ({
  id: post.postId,
  authorId: post.authorId,
  title: post.title,
  author: post.author,
  image: post.image,
  category: ['Photography', 'Editorial', 'Reflection', 'Archive'][index % 4]
})

onMounted(() => {
  if (feedStore.posts.length === 0) {
    void feedStore.fetchNextPage()
  }
})

const feedCards = computed(() => feedStore.posts.map(toFeedPreview))
const cards = computed(() => (feedCards.value.length > 0 ? feedCards.value : fallbackCards))
const activeTab = computed(
  () => feedTabs.find((item) => item.value === feedStore.activeFeed) ?? feedTabs[0]
)
const inlineStatus = computed(() => {
  if (feedStore.error) return '实时内容暂时不可用，当前展示的是原型回退内容。'
  if (feedStore.loading && feedStore.posts.length === 0) return '正在加载最新帖子。'
  return ''
})
const openPost = (card: FeedPreviewCard) => {
  if (card.id.startsWith('fallback-')) {
    return
  }
  void router.push(`/content/${card.id}`)
}

const openAuthor = (card: FeedPreviewCard) => {
  if (!card.authorId) {
    return
  }
  void router.push(`/user/${card.authorId}`)
}

const goSearch = (nextKeyword?: string) => {
  const keyword = (nextKeyword ?? searchQuery.value).trim()
  void router.push(keyword ? { path: '/search', query: { q: keyword } } : '/search')
}

const selectFeed = (nextFeed: FeedViewMode) => {
  void feedStore.setFeed(nextFeed)
}

const loadMore = () => {
  if (feedStore.loading || !feedStore.hasMore) {
    return
  }
  void feedStore.fetchNextPage()
}
</script>

<template>
  <PrototypeShell>
    <article data-prototype-home class="space-y-12 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="space-y-8">
          <div class="flex justify-center">
            <div class="inline-flex items-center gap-2 rounded-full border border-prototype-line bg-prototype-surface/90 p-1.5 shadow-[0_18px_60px_rgba(27,31,31,0.06)]">
              <button
                v-for="tab in feedTabs"
                :key="tab.value"
                type="button"
                class="rounded-full px-6 py-3 text-sm font-semibold tracking-[-0.01em] transition"
                :class="feedStore.activeFeed === tab.value
                  ? 'bg-prototype-ink text-prototype-surface shadow-[0_10px_24px_rgba(27,31,31,0.16)]'
                  : 'text-prototype-muted hover:text-prototype-ink'"
                @click="selectFeed(tab.value)"
              >
                {{ tab.label }}
              </button>
            </div>
          </div>

          <div class="grid gap-8 lg:grid-cols-[minmax(0,1fr),20rem] lg:items-end">
            <div class="space-y-4 text-center lg:text-left">
              <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
                {{ activeTab.eyebrow }}
              </p>
              <h1 class="mx-auto max-w-[12ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl lg:mx-0">
                {{ activeTab.heading }}
              </h1>
              <p class="mx-auto max-w-2xl text-base leading-8 text-prototype-muted lg:mx-0">
                {{ activeTab.description }}
              </p>
            </div>

            <div class="w-full max-w-[22rem] lg:justify-self-end">
              <SearchInput
                v-model="searchQuery"
                :is-expanded="searchExpanded"
                placeholder="Search sanctuary..."
                @expand="searchExpanded = true"
                @collapse="searchExpanded = false"
                @search="goSearch"
              />
            </div>
          </div>

          <p v-if="inlineStatus" class="max-w-2xl text-sm leading-7 text-prototype-muted">
            {{ inlineStatus }}
          </p>
        </div>
      </PrototypeContainer>

      <PrototypeContainer class="grid gap-x-10 gap-y-12 md:grid-cols-2 xl:grid-cols-3">
        <button
          v-for="card in cards"
          :key="card.id"
          type="button"
          class="group text-left transition hover:-translate-y-1.5"
          @click="openPost(card)"
        >
          <div class="mb-5 aspect-[4/5] overflow-hidden rounded-[1.2rem] bg-prototype-bg shadow-[0_22px_44px_rgba(27,31,31,0.08)]">
            <img :src="card.image" :alt="card.title" class="h-full w-full object-cover transition duration-700 group-hover:scale-[1.04]">
          </div>
          <div class="space-y-3 px-1">
            <div class="flex items-start justify-between gap-4">
              <h2 class="max-w-[16ch] font-headline text-[1.45rem] leading-8 tracking-[-0.03em] text-prototype-ink">
                {{ card.title }}
              </h2>
              <span class="rounded-full bg-prototype-surface px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-prototype-muted">
                {{ card.category }}
              </span>
            </div>
            <button
              v-if="card.authorId"
              :data-author-link="card.authorId"
              type="button"
              class="text-left text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted transition hover:text-prototype-ink"
              @click.stop="openAuthor(card)"
            >
              {{ card.author }}
            </button>
            <p
              v-else
              class="text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted"
            >
              {{ card.author }}
            </p>
          </div>
        </button>
      </PrototypeContainer>

      <PrototypeContainer width="content" class="flex justify-center">
        <ZenButton
          v-if="feedStore.posts.length > 0 && feedStore.hasMore"
          variant="secondary"
          :disabled="feedStore.loading"
          @click="loadMore"
        >
          {{ feedStore.loading ? '加载中...' : '加载更多帖子' }}
        </ZenButton>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
