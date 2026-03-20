<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const navTo = (path: string) => {
  router.push(path)
}
</script>

<template>
  <div class="dock-wrapper">
    <div class="dock-container">
      <div 
        class="dock-item" 
        :class="{ 'active': route.path === '/' }"
        @click="navTo('/')"
      >🏠</div>
      <div 
        class="dock-item" 
        :class="{ 'active': route.path === '/search' }"
        @click="navTo('/search')"
      >🧭</div>
      <div 
        class="dock-item publish-item" 
        @click="navTo('/publish')"
      >+</div>
      <div 
        class="dock-item" 
        :class="{ 'active': route.path === '/notifications' }"
        @click="navTo('/notifications')"
      >🔔</div>
      <div 
        class="dock-item" 
        :class="{ 'active': route.path === '/profile' }"
        @click="navTo('/profile')"
      >👤</div>
    </div>
  </div>
</template>

<style scoped>
.dock-wrapper {
  position: fixed;
  bottom: 0;
  left: 0;
  width: 100%;
  display: flex;
  justify-content: center;
  padding-bottom: calc(24px + var(--safe-area-inset-bottom, 20px));
  z-index: 1000;
  pointer-events: none;
}

.dock-container {
  background: var(--apple-blur-bg);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  padding: 10px 20px;
  border-radius: 36px;
  display: flex;
  gap: 24px;
  box-shadow: 0 4px 32px rgba(0, 0, 0, 0.08);
  border: 0.5px solid rgba(0, 0, 0, 0.05);
  pointer-events: auto;
  align-items: center;
}

.publish-item {
  font-size: 32px !important;
  font-weight: 300;
  color: var(--apple-accent);
  opacity: 1 !important;
}

.dock-item {
  font-size: 24px;
  opacity: 0.6;
  cursor: pointer;
  transition: transform 0.2s var(--spring-easing), opacity 0.2s ease;
  user-select: none;
}

.dock-item:active {
  transform: scale(0.85);
  opacity: 0.3;
}

.dock-item.active {
  opacity: 1;
  position: relative;
}

.dock-item.active::after {
  content: "";
  position: absolute;
  bottom: -6px;
  left: 50%;
  transform: translateX(-50%);
  width: 4px;
  height: 4px;
  background: var(--apple-text);
  border-radius: 50%;
}
</style>
