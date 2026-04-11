<template>
  <header class="fixed inset-x-0 top-0 z-40 border-b border-prototype-line bg-prototype-bg/95 backdrop-blur-sm">
    <PrototypeContainer
      as="div"
      class="flex min-h-[88px] items-center justify-between gap-6"
    >
      <div class="flex items-center gap-10">
        <a
          href="/"
          class="font-headline text-[1.75rem] tracking-[-0.04em] text-prototype-ink"
        >
          Nexus
        </a>

        <nav class="hidden items-center gap-6 md:flex">
          <a href="/" class="text-sm font-medium text-prototype-ink transition hover:text-prototype-accent">
            Gallery
          </a>
          <a href="/search" class="text-sm font-medium text-prototype-muted transition hover:text-prototype-ink">
            Search
          </a>
          <a href="/notifications" class="text-sm font-medium text-prototype-muted transition hover:text-prototype-ink">
            Notifications
          </a>
        </nav>
      </div>

      <div class="flex items-center gap-3">
        <a
          href="/publish"
          class="inline-flex min-h-[40px] items-center rounded-full border border-prototype-line px-4 text-sm font-medium text-prototype-ink transition hover:border-prototype-ink"
        >
          Publish
        </a>
        <a
          href="/profile"
          class="inline-flex h-10 w-10 items-center justify-center rounded-full border border-prototype-line text-sm font-semibold text-prototype-ink transition hover:border-prototype-ink"
          aria-label="Profile"
        >
          Me
        </a>
      </div>
    </PrototypeContainer>
  </header>
</template>

<script setup lang="ts">
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
</script>
