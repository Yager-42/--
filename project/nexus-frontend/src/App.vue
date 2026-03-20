<script setup lang="ts">
// Welcome to Nexus Frontend - Apple Style
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="apple-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
@import './assets/main.css';

.apple-fade-enter-active,
.apple-fade-leave-active {
  transition: opacity 0.4s var(--spring-easing);
}

.apple-fade-enter-from,
.apple-fade-leave-to {
  opacity: 0;
}
</style>
