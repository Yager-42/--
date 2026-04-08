<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const items = [
  { path: '/', label: '首页', icon: 'home' },
  { path: '/search', label: '搜索', icon: 'search' },
  { path: '/publish', label: '发布', icon: 'plus' },
  { path: '/notifications', label: '通知', icon: 'bell' },
  { path: '/profile', label: '我的', icon: 'user' }
] as const

const isActive = (path: string) => {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

const iconPath = computed(() => ({
  home: 'M4 11.5 12 4l8 7.5V20a1 1 0 0 1-1 1h-4.7v-5.5h-4.6V21H5a1 1 0 0 1-1-1v-8.5Z',
  search: 'M11 4a7 7 0 1 0 4.6 12.3L20 20.7 21.3 19l-4.4-4.4A7 7 0 0 0 11 4Z',
  plus: 'M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5Z',
  bell: 'M12 3a5.5 5.5 0 0 0-5.5 5.5v3.2l-1.8 2.7A1 1 0 0 0 5.5 16h13a1 1 0 0 0 .8-1.6l-1.8-2.7V8.5A5.5 5.5 0 0 0 12 3Zm0 18a2.6 2.6 0 0 0 2.3-1.4H9.7A2.6 2.6 0 0 0 12 21Z',
  user: 'M12 3a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9Zm0 11c-4.1 0-7.5 2-7.5 4.6V20A2 2 0 0 0 6.5 22h11a2 2 0 0 0 2-2v-1.4C19.5 16 16.1 14 12 14Z'
}))
</script>

<template>
  <nav class="dock" aria-label="主导航">
    <div class="dock-inner">
      <button
        v-for="item in items"
        :key="item.path"
        class="dock-item"
        :class="{ active: isActive(item.path), publish: item.path === '/publish' }"
        type="button"
        :aria-current="isActive(item.path) ? 'page' : undefined"
        @click="router.push(item.path)"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path :d="iconPath[item.icon]" fill="currentColor" />
        </svg>
        <span>{{ item.label }}</span>
      </button>
    </div>
  </nav>
</template>

<style scoped>
.dock {
  position: fixed;
  inset: auto 0 0 0;
  padding: 0 12px calc(var(--safe-bottom) + 10px);
  z-index: 28;
  pointer-events: none;
}

.dock-inner {
  width: min(980px, 100%);
  margin: 0 auto;
  height: var(--dock-height);
  border: 1px solid var(--border-soft);
  background: var(--bg-overlay);
  backdrop-filter: blur(16px);
  border-radius: 20px;
  box-shadow: var(--shadow-elevated);
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  pointer-events: auto;
}

.dock-item {
  min-height: 56px;
  margin: 8px 6px;
  border-radius: 14px;
  color: var(--text-muted);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 3px;
  font-size: 0.72rem;
  font-weight: 600;
  transition: color 180ms ease, background 180ms ease, transform 180ms ease;
}

.dock-item svg {
  width: 20px;
  height: 20px;
}

.dock-item.active {
  color: var(--brand-primary);
  background: rgba(225, 29, 72, 0.08);
}

.dock-item.publish {
  color: var(--brand-accent);
}

.dock-item:active {
  transform: translateY(1px);
}
</style>
