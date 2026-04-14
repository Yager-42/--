<script setup lang="ts">
import ZenButton from '@/components/primitives/ZenButton.vue'

defineProps<{
  decisionId: string
  punishId: string
  content: string
  loading?: boolean
  unavailable?: boolean
}>()

defineEmits<{
  (event: 'update:content', value: string): void
  (event: 'submit'): void
}>()
</script>

<template>
  <section class="grid gap-6 rounded-[1.75rem] border border-prototype-line bg-prototype-bg p-6">
    <div class="grid gap-3 rounded-[1.5rem] border border-prototype-line bg-prototype-surface p-5 text-sm leading-7 text-prototype-muted">
      <p><span>事件编号：</span><strong class="text-prototype-ink">{{ decisionId || '未提供' }}</strong></p>
      <p><span>处罚编号：</span><strong class="text-prototype-ink">{{ punishId || '未提供' }}</strong></p>
    </div>

    <label class="grid gap-2">
      <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">申诉理由</span>
      <textarea
        :value="content"
        :disabled="unavailable"
        placeholder="请完整描述你希望申诉的事实与依据。"
        class="min-h-[16rem] rounded-[1.75rem] border border-prototype-line bg-prototype-surface px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink disabled:cursor-not-allowed disabled:opacity-60"
        @input="$emit('update:content', ($event.target as HTMLTextAreaElement).value)"
      />
    </label>

    <ZenButton
      variant="primary"
      class="justify-self-end"
      :disabled="loading || unavailable || !content.trim()"
      @click="$emit('submit')"
    >
      {{ loading ? '提交中...' : '提交申诉' }}
    </ZenButton>
  </section>
</template>
