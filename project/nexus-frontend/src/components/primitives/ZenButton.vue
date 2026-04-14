<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'text'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    block?: boolean
    class?: string
  }>(),
  {
    variant: 'secondary',
    type: 'button',
    disabled: false,
    block: false,
    class: ''
  }
)
</script>

<template>
  <button
    :type="type"
    :disabled="disabled"
    class="inline-flex min-h-[46px] items-center justify-center gap-2 rounded-full px-5 text-sm font-semibold transition duration-200 disabled:cursor-not-allowed disabled:opacity-50"
    :class="[
      block ? 'w-full' : '',
      variant === 'primary'
        ? 'zen-gradient-cta'
        : variant === 'secondary'
          ? 'border border-outline-variant/20 bg-white/70 text-on-surface shadow-soft hover:bg-surface-container-lowest'
          : 'text-on-surface-variant hover:text-primary',
      props.class
    ]"
  >
    <slot />
  </button>
</template>
