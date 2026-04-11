<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    as?: string
    width?: 'shell' | 'content' | 'reading'
    padded?: boolean
  }>(),
  {
    as: 'div',
    width: 'shell',
    padded: true
  }
)

const widthClass = computed(() => {
  if (props.width === 'reading') return 'max-w-reading'
  if (props.width === 'content') return 'max-w-content'
  return 'max-w-shell'
})
</script>

<template>
  <component
    :is="as"
    class="mx-auto w-full"
    :class="[widthClass, padded ? 'px-8' : 'px-0']"
  >
    <slot />
  </component>
</template>
