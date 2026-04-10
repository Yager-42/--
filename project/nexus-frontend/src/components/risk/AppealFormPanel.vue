<script setup lang="ts">
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
  <section class="appeal-panel">
    <div class="appeal-panel__meta">
      <p><span>事件编号：</span><strong>{{ decisionId || '未提供' }}</strong></p>
      <p><span>处罚编号：</span><strong>{{ punishId || '未提供' }}</strong></p>
    </div>

    <label class="appeal-panel__field">
      <span>申诉理由</span>
      <textarea
        :value="content"
        :disabled="unavailable"
        placeholder="请完整描述你希望申诉的事实与依据。"
        @input="$emit('update:content', ($event.target as HTMLTextAreaElement).value)"
      />
    </label>

    <button
      type="button"
      class="primary-btn appeal-panel__submit"
      :disabled="loading || unavailable || !content.trim()"
      @click="$emit('submit')"
    >
      {{ loading ? '提交中...' : '提交申诉' }}
    </button>
  </section>
</template>

<style scoped>
.appeal-panel {
  display: grid;
  gap: 1rem;
}

.appeal-panel__meta {
  display: grid;
  gap: 0.35rem;
  padding: 1rem;
  border-radius: var(--radius-md);
  background: rgba(255, 251, 245, 0.72);
  border: 1px solid var(--border-ghost);
  color: var(--text-secondary);
}

.appeal-panel__field {
  display: grid;
  gap: 0.45rem;
}

.appeal-panel__field span {
  color: var(--text-secondary);
}

.appeal-panel__submit {
  justify-self: end;
}

@media (max-width: 640px) {
  .appeal-panel__submit {
    justify-self: stretch;
  }
}
</style>
