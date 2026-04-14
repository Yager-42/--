<script setup lang="ts">
import { nextTick } from 'vue'

const focusMain = async () => {
  await nextTick()
  document.getElementById('app-main')?.focus()
}
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="route-fade" mode="out-in" @after-enter="focusMain">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
.route-fade-enter-active,
.route-fade-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.route-fade-enter-from,
.route-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
