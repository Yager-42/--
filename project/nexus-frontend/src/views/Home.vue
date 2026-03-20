<script setup lang="ts">
import { ref } from 'vue'
import FeedContainer from '@/components/FeedContainer.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import TheDock from '@/components/TheDock.vue'
import PostDetailOverlay from '@/components/PostDetailOverlay.vue'

const selectedPost = ref<any>(null)
const isDetailOpen = ref(false)

const openDetail = (post: any) => {
  selectedPost.value = post
  isDetailOpen.value = true
}

const closeDetail = () => {
  isDetailOpen.value = false
}
</script>

<template>
  <div class="home-view">
    <TheNavBar />
    <FeedContainer @select="openDetail" />
    <TheDock v-show="!isDetailOpen" />
    
    <transition>
      <PostDetailOverlay 
        :post="selectedPost" 
        :is-open="isDetailOpen" 
        @close="closeDetail" 
      />
    </transition>
  </div>
</template>

<style scoped>
.home-view {
  height: 100vh;
  width: 100%;
  position: relative;
  overflow: hidden;
  background: white;
}

/* Detail overlay transition */
.v-enter-active,
.v-leave-active {
  transition: opacity 0.4s ease;
}

.v-enter-from,
.v-leave-to {
  opacity: 0;
}
</style>
