<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    as?: 'input' | 'textarea' | 'select'
    label?: string
    modelValue?: string
    type?: string
    placeholder?: string
    rows?: number
    disabled?: boolean
    class?: string
  }>(),
  {
    as: 'input',
    label: '',
    modelValue: '',
    type: 'text',
    placeholder: '',
    rows: 5,
    disabled: false,
    class: ''
  }
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
}>()

const fieldTag = computed(() => props.as)

const updateValue = (event: Event) => {
  emit('update:modelValue', (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value)
}
</script>

<template>
  <label class="grid gap-2">
    <span v-if="label" class="text-xs font-semibold uppercase tracking-[0.22em] text-on-surface-variant">
      {{ label }}
    </span>

    <component
      :is="fieldTag"
      :value="modelValue"
      :type="as === 'input' ? type : undefined"
      :rows="as === 'textarea' ? rows : undefined"
      :disabled="disabled"
      :placeholder="placeholder"
      class="w-full rounded-2xl border border-outline-variant/10 bg-surface-container-low px-5 py-4 text-sm text-on-surface placeholder:text-outline-variant/80 transition duration-200 focus:border-primary/20 focus:bg-surface-container-lowest focus:outline-none"
      :class="props.class"
      @input="updateValue"
    >
      <slot />
    </component>
  </label>
</template>
