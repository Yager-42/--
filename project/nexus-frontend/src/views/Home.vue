<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FeedCardViewModel } from '@/api/feed'
import { useFeedStore } from '@/store/feed'
import FeedContainer from '@/components/FeedContainer.vue'
import HomeHeroCard from '@/components/home/HomeHeroCard.vue'
import TheDock from '@/components/TheDock.vue'
import TheNavBar from '@/components/TheNavBar.vue'

const router = useRouter()
const feedStore = useFeedStore()

onMounted(() => {
  if (feedStore.posts.length === 0) {
    void feedStore.fetchNextPage()
  }
})

const heroPost = computed(() => feedStore.posts[0] ?? null)
const discoveryPosts = computed(() => feedStore.posts.slice(1, 4))

const openDetail = (post: FeedCardViewModel) => {
  void router.push(`/content/${post.postId}`)
}
</script>

<template>
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main page-main--dock">
      <section class="grid gap-8">
        <div class="grid gap-5 md:grid-cols-[minmax(0,1.2fr),18rem] md:items-end">
          <div class="grid gap-3">
            <p class="section-kicker">Curated Feed</p>
            <h1 class="section-title text-balance">A quieter way to discover what matters now.</h1>
            <p class="section-copy max-w-3xl">
              每一次进入首页，内容都会被重新整理成更适合阅读与停留的节奏，而不是一面密集的信息墙。
            </p>
          </div>

          <aside class="paper-panel hidden gap-3 p-6 md:grid">
            <p class="section-kicker">Editorial Note</p>
            <p class="text-sm leading-7 text-on-surface">
              内容流不是均质卡片墙，而是更接近原型里的 editorial rhythm：主叙事、侧栏线索、再进入内容流。
            </p>
          </aside>
        </div>

        <HomeHeroCard v-if="heroPost" :post="heroPost" @select="openDetail" />

        <div v-if="discoveryPosts.length" class="grid gap-4 md:grid-cols-3">
          <button
            v-for="post in discoveryPosts"
            :key="post.postId"
            type="button"
            class="soft-panel grid min-h-[180px] content-end gap-2 p-5 text-left transition hover:-translate-y-0.5 hover:shadow-float"
            @click="openDetail(post)"
          >
            <span class="section-kicker">{{ post.author }}</span>
            <span class="text-xl font-bold tracking-tight text-on-surface">{{ post.title }}</span>
            <span class="text-sm leading-6 text-on-surface-variant line-clamp-3">{{ post.body }}</span>
          </button>
        </div>

        <FeedContainer :featured-post-id="heroPost?.postId" @select="openDetail" />
      </section>
    </main>

    <TheDock />
  </div>
</template>
