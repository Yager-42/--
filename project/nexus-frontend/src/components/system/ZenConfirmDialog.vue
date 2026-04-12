<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import ZenButton from '@/components/primitives/ZenButton.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    body?: string
    confirmLabel?: string
    cancelLabel?: string
    confirmVariant?: 'primary' | 'secondary'
  }>(),
  {
    body: '',
    confirmLabel: 'Confirm',
    cancelLabel: 'Cancel',
    confirmVariant: 'primary'
  }
)

const emit = defineEmits<{
  (event: 'confirm'): void
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
        class="fixed inset-0 z-[80] grid place-items-center bg-[rgba(16,20,20,0.28)] px-6"
        @click.self="emit('close')"
      >
        <div
          class="w-full max-w-[28rem] rounded-[2rem] border border-prototype-line bg-prototype-surface p-7 shadow-[0_30px_90px_rgba(27,31,31,0.18)]"
          role="alertdialog"
          aria-modal="true"
        >
          <div class="space-y-3">
            <p class="font-headline text-[1.9rem] tracking-[-0.04em] text-prototype-ink">
              {{ title }}
            </p>
            <p v-if="body" class="text-sm leading-7 text-prototype-muted">
              {{ body }}
            </p>
          </div>

          <div class="mt-8 flex justify-end gap-3">
            <ZenButton data-confirm-cancel variant="secondary" @click="emit('close')">
              {{ cancelLabel }}
            </ZenButton>
            <ZenButton
              data-confirm-accept
              :variant="confirmVariant"
              @click="emit('confirm')"
            >
              {{ confirmLabel }}
            </ZenButton>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
