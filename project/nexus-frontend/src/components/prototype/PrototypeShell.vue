<template>
  <div class="min-h-screen bg-prototype-bg text-prototype-ink">
    <PrototypeNav data-prototype-nav />
    <main data-prototype-main class="mx-auto w-full max-w-shell px-8 pt-[88px] pb-20">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
import PrototypeNav from '@/components/prototype/PrototypeNav.vue'
</script>
