<template>
  <PrototypeContainer
    data-reading-column
    width="reading"
    class="space-y-6 text-[15px] leading-7 text-prototype-muted"
  >
    <slot />
  </PrototypeContainer>
</template>

<script setup lang="ts">
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
</script>
