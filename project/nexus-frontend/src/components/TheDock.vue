<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

const route = useRoute()
const router = useRouter()

const items = [
  { path: '/', label: 'Gallery', icon: 'grid_view' },
  { path: '/search', label: 'Search', icon: 'search' },
  { path: '/publish', label: 'Create', icon: 'add_box' },
  { path: '/notifications', label: 'Activity', icon: 'favorite' },
  { path: '/profile', label: 'Profile', icon: 'person' }
] as const

const isActive = (path: string) => {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

const navigate = (path: string) => {
  void router.push(path)
}
</script>

<template>
  <nav class="fixed inset-x-0 bottom-0 z-50 px-4 pb-5 md:hidden" aria-label="主导航">
    <div class="mx-auto grid max-w-editorial grid-cols-5 gap-1 rounded-[32px] border border-outline-variant/15 bg-white/80 p-2 shadow-[0_-8px_30px_rgba(0,0,0,0.04)] backdrop-blur-editorial">
      <button
        v-for="item in items"
        :key="item.path"
        type="button"
        class="flex min-h-[68px] flex-col items-center justify-center gap-1 rounded-[24px] px-2 py-2 text-[11px] font-medium uppercase tracking-[0.18em] transition"
        :class="isActive(item.path) ? 'bg-surface-container text-on-surface' : 'text-on-surface-variant hover:text-on-surface'"
        :aria-current="isActive(item.path) ? 'page' : undefined"
        @click="navigate(item.path)"
      >
        <ZenIcon :name="item.icon" :size="20" :fill="isActive(item.path)" />
        <span>{{ item.label }}</span>
      </button>
    </div>
  </nav>
</template>
