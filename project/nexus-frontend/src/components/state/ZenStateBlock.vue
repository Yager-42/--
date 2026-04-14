<script setup lang="ts">
import { computed } from 'vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import { useRouteStateCopy, type RouteStateVariant } from '@/composables/useRouteStateCopy'

const props = withDefaults(
  defineProps<{
    variant: RouteStateVariant
    title?: string
    body?: string
    actionLabel?: string
    compact?: boolean
    surface?: boolean
  }>(),
  {
    title: '',
    body: '',
    actionLabel: '',
    compact: false,
    surface: true
  }
)

const emit = defineEmits<{
  (event: 'action'): void
}>()

const { getCopy } = useRouteStateCopy()

const resolvedCopy = computed(() =>
  getCopy(props.variant, {
    title: props.title || undefined,
    body: props.body || undefined,
    actionLabel: props.actionLabel || undefined
  })
)

const iconName = computed(() => {
  switch (props.variant) {
    case 'loading':
      return 'progress_activity'
    case 'empty':
      return 'gallery_thumbnail'
    case 'restricted':
      return 'lock'
    case 'request-failure':
      return 'warning'
    case 'no-results':
      return 'search_off'
    case 'upload-failure':
      return 'upload'
  }
})
</script>

<template>
  <section
    class="grid justify-items-center text-center"
    :class="[
      compact ? 'gap-3 p-4' : 'gap-4 p-6 md:p-8',
      surface ? 'rounded-[1.75rem] border border-prototype-line bg-prototype-surface shadow-soft' : ''
    ]"
  >
    <div class="grid h-12 w-12 place-items-center rounded-2xl bg-prototype-bg text-prototype-ink">
      <div
        v-if="variant === 'loading'"
        class="h-5 w-5 animate-spin rounded-full border-2 border-outline-variant/25 border-t-primary"
      />
      <ZenIcon v-else :name="iconName" :size="22" />
    </div>

    <div class="grid max-w-[34rem] gap-1.5">
      <p class="section-kicker">{{ resolvedCopy.eyebrow }}</p>
      <h3 class="text-xl font-bold tracking-tight text-prototype-ink md:text-2xl">{{ resolvedCopy.title }}</h3>
      <p class="text-sm leading-7 text-prototype-muted">{{ resolvedCopy.body }}</p>
    </div>

    <ZenButton
      v-if="resolvedCopy.actionLabel"
      variant="secondary"
      class="min-w-[132px]"
      @click="emit('action')"
    >
      {{ resolvedCopy.actionLabel }}
    </ZenButton>
  </section>
</template>
