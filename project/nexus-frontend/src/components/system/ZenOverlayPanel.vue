<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

defineOptions({
  inheritAttrs: false
})

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    widthClass?: string
    closeLabel?: string
  }>(),
  {
    title: '',
    description: '',
    widthClass: 'max-w-[28rem]',
    closeLabel: 'Close panel'
  }
)

const emit = defineEmits<{
  (event: 'close'): void
}>()

const BODY_LOCK_DATA_KEY = 'zenOverlayLocks'
let ownsBodyLock = false

const acquireBodyLock = () => {
  if (typeof document === 'undefined' || ownsBodyLock) {
    return
  }

  const currentCount = Number(document.body.dataset[BODY_LOCK_DATA_KEY] ?? '0')
  const nextCount = currentCount + 1

  document.body.dataset[BODY_LOCK_DATA_KEY] = String(nextCount)
  document.body.classList.add('overflow-hidden')
  ownsBodyLock = true
}

const releaseBodyLock = () => {
  if (typeof document === 'undefined' || !ownsBodyLock) {
    return
  }

  const currentCount = Number(document.body.dataset[BODY_LOCK_DATA_KEY] ?? '0')
  const nextCount = Math.max(0, currentCount - 1)

  if (nextCount === 0) {
    delete document.body.dataset[BODY_LOCK_DATA_KEY]
    document.body.classList.remove('overflow-hidden')
  } else {
    document.body.dataset[BODY_LOCK_DATA_KEY] = String(nextCount)
  }

  ownsBodyLock = false
}

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && props.open) {
    emit('close')
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      acquireBodyLock()
      window.addEventListener('keydown', handleKeydown)
      return
    }

    releaseBodyLock()
    window.removeEventListener('keydown', handleKeydown)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  releaseBodyLock()
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-[70] flex justify-end bg-[rgba(16,20,20,0.24)] px-6 py-6"
        @click.self="emit('close')"
      >
        <Transition
          enter-active-class="transition duration-200 ease-out"
          enter-from-class="translate-x-6 opacity-0"
          enter-to-class="translate-x-0 opacity-100"
          leave-active-class="transition duration-150 ease-in"
          leave-from-class="translate-x-0 opacity-100"
          leave-to-class="translate-x-6 opacity-0"
        >
          <aside
            v-bind="$attrs"
            class="flex h-full w-full flex-col overflow-hidden rounded-[2rem] border border-prototype-line bg-prototype-surface shadow-[0_30px_90px_rgba(27,31,31,0.18)]"
            :class="widthClass"
            role="dialog"
            aria-modal="true"
          >
            <header class="flex items-start justify-between gap-4 border-b border-prototype-line px-6 py-5">
              <div class="space-y-2">
                <p v-if="title" class="font-headline text-[1.8rem] tracking-[-0.04em] text-prototype-ink">
                  {{ title }}
                </p>
                <p v-if="description" class="max-w-[32ch] text-sm leading-7 text-prototype-muted">
                  {{ description }}
                </p>
              </div>

              <button
                type="button"
                class="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-prototype-line text-prototype-muted transition hover:text-prototype-ink"
                :aria-label="closeLabel"
                @click="emit('close')"
              >
                <ZenIcon name="close" :size="18" />
              </button>
            </header>

            <div class="flex-1 overflow-y-auto px-6 py-6">
              <slot />
            </div>

            <footer v-if="$slots.footer" class="border-t border-prototype-line px-6 py-5">
              <slot name="footer" />
            </footer>
          </aside>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>
