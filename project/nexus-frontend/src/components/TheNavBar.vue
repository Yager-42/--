<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import SearchInput from './SearchInput.vue'

const router = useRouter()
const isSearchExpanded = ref(false)

const onSearchExpand = () => {
  isSearchExpanded.value = true
}

const onSearchCollapse = () => {
  isSearchExpanded.value = false
}

const onSearch = (keyword: string) => {
  router.push({ path: '/search', query: { q: keyword } })
  isSearchExpanded.value = false
}
</script>

<template>
  <nav class="nav-bar">
    <div class="nav-content">
      <div v-show="!isSearchExpanded" class="logo" @click="router.push('/')">Nexus</div>
      <div class="search-wrapper">
        <SearchInput 
          :is-expanded="isSearchExpanded"
          @expand="onSearchExpand"
          @collapse="onSearchCollapse"
          @search="onSearch"
        />
      </div>
      <div v-show="!isSearchExpanded" class="actions">
        <div class="icon-btn" @click="router.push('/notifications')">🔔</div>
        <div class="icon-btn" @click="router.push('/profile')">👤</div>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.nav-bar {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 44px;
  background: var(--apple-blur-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  z-index: 1000;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  padding: 0 16px;
}

.nav-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: relative;
}

.logo {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.5px;
  cursor: pointer;
}

.search-wrapper {
  position: absolute;
  right: 0;
  display: flex;
  align-items: center;
}

.actions {
  display: flex;
  gap: 16px;
}

.icon-btn {
  font-size: 20px;
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.icon-btn:active {
  opacity: 0.5;
}
</style>
