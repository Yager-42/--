<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import SearchInput from './SearchInput.vue'

const router = useRouter()
const searchOpen = ref(false)

const title = computed(() => (searchOpen.value ? '搜索' : 'Nexus'))

const onSearch = (keyword: string) => {
  router.push({ path: '/search', query: { q: keyword } })
  searchOpen.value = false
}
</script>

<template>
  <header class="top-nav" role="banner">
    <div class="nav-inner">
      <button
        v-if="!searchOpen"
        class="brand-btn"
        type="button"
        aria-label="回到首页"
        @click="router.push('/')"
      >
        {{ title }}
      </button>
      <span v-else class="search-title">{{ title }}</span>

      <SearchInput
        :is-expanded="searchOpen"
        @expand="searchOpen = true"
        @collapse="searchOpen = false"
        @search="onSearch"
      />

      <div v-if="!searchOpen" class="actions" aria-label="快捷操作">
        <button
          class="icon-btn"
          type="button"
          aria-label="通知"
          @click="router.push('/notifications')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 3a6 6 0 0 0-6 6v3.8l-1.5 2.3A1 1 0 0 0 5.3 17h13.4a1 1 0 0 0 .8-1.6L18 12.8V9a6 6 0 0 0-6-6Zm0 19a2.7 2.7 0 0 0 2.45-1.5h-4.9A2.7 2.7 0 0 0 12 22Z" fill="currentColor" />
          </svg>
        </button>
        <button
          class="icon-btn"
          type="button"
          aria-label="个人主页"
          @click="router.push('/profile')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 12c-4.42 0-8 2.24-8 5v1a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1c0-2.76-3.58-5-8-5Z" fill="currentColor" />
          </svg>
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.top-nav {
  position: fixed;
  inset: 0 0 auto 0;
  height: calc(var(--header-height) + var(--safe-top));
  padding-top: var(--safe-top);
  background: var(--bg-overlay);
  border-bottom: 1px solid var(--border-soft);
  backdrop-filter: blur(14px);
  z-index: 30;
}

.nav-inner {
  height: var(--header-height);
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 12px;
  width: min(1080px, 100% - 24px);
  margin: 0 auto;
}

.brand-btn {
  border: none;
  background: none;
  color: var(--text-primary);
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 1.2rem;
  font-weight: 800;
  letter-spacing: 0.02em;
  text-align: left;
  min-height: 44px;
}

.search-title {
  font-weight: 700;
}

.actions {
  display: flex;
  gap: 8px;
}

.icon-btn {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  color: var(--text-primary);
  border: 1px solid transparent;
  transition: background 180ms ease;
}

.icon-btn:hover {
  background: var(--bg-elevated);
  border-color: var(--border-soft);
}

.icon-btn:active {
  transform: translateY(1px);
}

.icon-btn svg {
  width: 22px;
  height: 22px;
}
</style>
