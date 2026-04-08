<script setup lang="ts">
import { useRouter } from 'vue-router'
import FeedContainer from '@/components/FeedContainer.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'
import type { FeedCardViewModel } from '@/api/feed'

const router = useRouter()

const openDetail = (post: FeedCardViewModel) => {
  void router.push(`/content/${post.postId}`)
}
</script>

<template>
  <div class="page-shell with-full-nav">
    <TheNavBar />

    <main class="page-content">
      <FeedContainer @select="openDetail" />
    </main>

    <TheDock />
  </div>
</template>


