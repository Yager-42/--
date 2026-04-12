<script setup lang="ts">
import ZenButton from '@/components/primitives/ZenButton.vue'

const props = withDefaults(
  defineProps<{
    blocking?: boolean
  }>(),
  {
    blocking: false
  }
)

const emit = defineEmits<{
  (event: 'block'): void
}>()
</script>

<template>
  <section
    data-profile-action-menu
    class="space-y-4 rounded-[1.5rem] border border-prototype-line bg-prototype-surface/95 p-6 shadow-[0_18px_40px_rgba(27,31,31,0.08)]"
  >
    <div class="space-y-2">
      <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
        Visitor actions
      </p>
      <h3 class="font-headline text-2xl tracking-[-0.03em] text-prototype-ink">
        Keep your profile boundary deliberate.
      </h3>
      <p class="text-sm leading-7 text-prototype-muted">
        If this author is not a fit, block them here and return to search without leaving the
        current profile shell.
      </p>
    </div>

    <ZenButton
      variant="secondary"
      block
      :disabled="props.blocking"
      @click="emit('block')"
    >
      {{ props.blocking ? '处理中...' : '屏蔽此用户' }}
    </ZenButton>
  </section>
</template>
