<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    name: string
    size?: number | string
    fill?: boolean
    weight?: number
    label?: string
    class?: string
  }>(),
  {
    size: 20,
    fill: false,
    weight: 350,
    label: '',
    class: ''
  }
)
</script>

<template>
  <span
    class="material-symbols-outlined inline-flex items-center justify-center align-middle"
    :class="props.class"
    :aria-hidden="label ? undefined : 'true'"
    :aria-label="label || undefined"
    :style="{
      fontSize: typeof size === 'number' ? `${size}px` : size,
      fontVariationSettings: `'FILL' ${fill ? 1 : 0}, 'wght' ${weight}, 'GRAD' 0, 'opsz' 24`
    }"
  >
    {{ name }}
  </span>
</template>
