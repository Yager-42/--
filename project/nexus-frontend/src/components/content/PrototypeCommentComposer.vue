<script setup lang="ts">
const props = defineProps<{
  avatarSrc: string
  modelValue: string
  sending: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
}>()

const updateValue = (event: Event) => {
  const target = event.target as HTMLTextAreaElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <div class="rounded-2xl border border-prototype-line bg-prototype-surface p-6">
    <div class="flex gap-4">
      <div class="h-10 w-10 shrink-0 overflow-hidden rounded-full">
        <img :src="avatarSrc" alt="Your avatar" class="h-full w-full object-cover">
      </div>
      <div class="flex-1 space-y-4">
        <textarea
          :value="props.modelValue"
          class="min-h-[88px] w-full resize-none border-none bg-transparent p-0 text-base leading-7 text-prototype-ink placeholder:text-prototype-muted/60 focus:ring-0"
          placeholder="Share your perspective..."
          @input="updateValue"
        />
        <div class="flex flex-col gap-3 border-t border-prototype-line pt-3 sm:flex-row sm:items-center sm:justify-between">
          <div class="flex gap-2 text-prototype-muted">
            <button type="button" class="p-2 transition hover:text-prototype-accent">
              <span class="material-symbols-outlined text-lg">image</span>
            </button>
            <button type="button" class="p-2 transition hover:text-prototype-accent">
              <span class="material-symbols-outlined text-lg">sentiment_satisfied</span>
            </button>
          </div>
          <button
            type="button"
            class="rounded-full bg-prototype-ink px-6 py-2.5 text-sm font-semibold text-prototype-surface transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="props.sending || !props.modelValue.trim()"
            @click="emit('submit')"
          >
            {{ props.sending ? 'Posting...' : 'Post Note' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
