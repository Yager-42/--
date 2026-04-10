<script setup lang="ts">
defineProps<{
  status: 'NORMAL' | 'LIMITED' | 'BANNED'
  capabilities: string[]
  appealReady: boolean
}>()

defineEmits<{
  (event: 'appeal'): void
}>()
</script>

<template>
  <section class="risk-overview">
    <p class="risk-overview__eyebrow">Risk Overview</p>
    <h1 class="text-large-title">当前账号状态：{{ status }}</h1>
    <p class="text-body text-secondary">
      当前状态与能力限制来自后端风险接口；只有带齐处罚参数时才允许进入可提交的申诉流程。
    </p>

    <div class="risk-overview__caps">
      <span v-for="item in capabilities" :key="item" class="risk-overview__chip">{{ item }}</span>
      <span v-if="capabilities.length === 0" class="risk-overview__chip">暂无限制说明</span>
    </div>

    <button
      type="button"
      class="primary-btn risk-overview__action"
      :disabled="!appealReady"
      @click="$emit('appeal')"
    >
      {{ appealReady ? '进入申诉' : '当前无可申诉案件' }}
    </button>
  </section>
</template>

<style scoped>
.risk-overview {
  display: grid;
  gap: 1rem;
}

.risk-overview__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.risk-overview__caps {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
}

.risk-overview__chip {
  min-height: 2.2rem;
  display: inline-flex;
  align-items: center;
  padding: 0 0.9rem;
  border-radius: 999px;
  background: rgba(255, 251, 245, 0.8);
  border: 1px solid var(--border-ghost);
  color: var(--text-secondary);
}

.risk-overview__action {
  justify-self: start;
}
</style>

