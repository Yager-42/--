<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    placeholder?: string
    submitLabel?: string
    replyToName?: string
    initialValue?: string
    isSubmitting?: boolean
  }>(),
  {
    placeholder: '写下你的评论。',
    submitLabel: '发送评论',
    replyToName: '',
    initialValue: '',
    isSubmitting: false
  }
)

const emit = defineEmits<{
  (event: 'submit', payload: string): void
  (event: 'cancel-reply'): void
}>()

const value = ref(props.initialValue)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

async function focusTextarea() {
  await nextTick()
  textareaRef.value?.focus()
}

watch(
  () => props.initialValue,
  (nextValue) => {
    value.value = nextValue
  }
)

onMounted(() => {
  void focusTextarea()
})

async function handleSubmit() {
  if (!value.value.trim()) {
    return
  }

  emit('submit', value.value.trim())
}
</script>

<template>
  <div class="space-y-3 rounded-3xl border border-nx-border bg-nx-surface-muted p-4">
    <div v-if="replyToName" class="flex items-center justify-between gap-3 rounded-2xl bg-white px-4 py-3">
      <p class="text-sm text-nx-text-muted">
        正在回复 <span class="font-medium text-nx-text">{{ replyToName }}</span>
      </p>
      <button
        data-test="comment-cancel-reply"
        type="button"
        class="text-sm font-medium text-nx-primary transition hover:opacity-80"
        @click="emit('cancel-reply')"
      >
        取消
      </button>
    </div>

    <textarea
      ref="textareaRef"
      v-model="value"
      data-test="comment-input"
      :disabled="isSubmitting"
      class="min-h-28 w-full rounded-3xl border border-nx-border bg-white px-4 py-3 text-sm leading-6 text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
      :placeholder="placeholder"
    />

    <div class="flex justify-end">
      <button
        data-test="comment-submit"
        type="button"
        :disabled="isSubmitting"
        class="inline-flex min-h-11 items-center justify-center rounded-full bg-nx-primary px-5 text-sm font-semibold text-white"
        @click="handleSubmit"
      >
        {{ isSubmitting ? '发送中...' : submitLabel }}
      </button>
    </div>
  </div>
</template>
