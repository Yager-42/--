<script setup lang="ts">
import ZenStateBlock from '@/components/state/ZenStateBlock.vue'
import type { RouteStateVariant } from '@/composables/useRouteStateCopy'

const props = withDefaults(
  defineProps<{
    variant: RouteStateVariant
    title?: string
    body?: string
    actionLabel?: string
    primaryLabel?: string
    compact?: boolean
    surface?: boolean
  }>(),
  {
    title: '',
    body: '',
    actionLabel: '',
    primaryLabel: '',
    compact: false,
    surface: true
  }
)

const emit = defineEmits<{
  (event: 'action'): void
  (event: 'primary'): void
}>()

const handleAction = () => {
  emit('action')
  emit('primary')
}
</script>

<template>
  <ZenStateBlock
    :variant="props.variant"
    :title="props.title"
    :body="props.body"
    :action-label="props.primaryLabel || props.actionLabel"
    :compact="props.compact"
    :surface="props.surface"
    @action="handleAction"
  />
</template>
