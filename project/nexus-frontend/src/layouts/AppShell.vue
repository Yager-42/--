<script setup lang="ts">
import AppLogo from '@/components/common/AppLogo.vue'
import AppSearchTrigger from '@/components/common/AppSearchTrigger.vue'
import MobileBottomNav from '@/components/nav/MobileBottomNav.vue'
import PrimarySidebar from '@/components/nav/PrimarySidebar.vue'
</script>

<template>
  <div class="min-h-screen bg-nx-bg">
    <div class="mx-auto flex max-w-[85rem] gap-6 px-4 py-6 lg:px-6">
      <PrimarySidebar />

      <div class="min-w-0 flex-1">
        <header class="mb-6 flex flex-col gap-4 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)] sm:flex-row sm:items-center sm:justify-between">
          <AppLogo />
          <AppSearchTrigger />
        </header>

        <main class="min-w-0 pb-24 lg:pb-8">
          <RouterView />
        </main>
      </div>

      <aside class="hidden w-72 shrink-0 xl:block">
        <div class="sticky top-6 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)]">
          <p class="text-sm font-medium text-nx-text">Discover</p>
          <p class="mt-2 text-sm leading-6 text-nx-text-muted">推荐位、热搜和辅助信息会在这里扩展。</p>
        </div>
      </aside>
    </div>

    <MobileBottomNav />
  </div>
</template>
