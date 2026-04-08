<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { submitAppeal } from '@/api/risk'

const router = useRouter()
const route = useRoute()

const appealContent = ref('')
const loading = ref(false)
const error = ref('')
const success = ref('')

const decisionId = typeof route.query.decisionId === 'string' ? route.query.decisionId : 'unknown'
const punishId = typeof route.query.punishId === 'string' ? route.query.punishId : 'unknown'

const handleSubmit = async () => {
  if (!appealContent.value.trim()) {
    error.value = '请填写申诉内容'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    await submitAppeal({
      decisionId,
      punishId,
      content: appealContent.value.trim()
    })
    success.value = '申诉已提交，我们会尽快处理。'
    setTimeout(() => {
      void router.back()
    }, 900)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell with-top-nav risk-page">
    <main class="page-content surface-card risk-card">
      <header>
        <h1 class="text-large-title">申诉中心</h1>
        <p class="text-secondary">如果你认为处罚存在误判，请完整描述实际情况。</p>
      </header>

      <section class="info-box">
        <p><span>事件编号：</span><strong>{{ decisionId }}</strong></p>
        <p><span>处罚编号：</span><strong>{{ punishId }}</strong></p>
      </section>

      <label class="field">
        <span>申诉理由</span>
        <textarea v-model="appealContent" placeholder="请详细说明申诉理由和补充证据" />
      </label>

      <p v-if="error" class="msg error">{{ error }}</p>
      <p v-if="success" class="msg success">{{ success }}</p>

      <div class="actions">
        <button class="secondary-btn" type="button" @click="router.back()">返回</button>
        <button class="primary-btn" type="button" :disabled="loading || !appealContent.trim()" @click="handleSubmit">
          {{ loading ? '提交中...' : '提交申诉' }}
        </button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.risk-page {
  display: grid;
}

.risk-card {
  padding: 20px;
  display: grid;
  gap: 12px;
}

.info-box {
  border: 1px solid #facc15;
  background: #fffbeb;
  border-radius: 12px;
  padding: 10px 12px;
  color: #854d0e;
  display: grid;
  gap: 4px;
}

.field {
  display: grid;
  gap: 6px;
}

.field span {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

textarea {
  min-height: 180px;
  resize: vertical;
  border-radius: 12px;
  border: 1px solid var(--border-soft);
  background: #fff;
  padding: 10px 12px;
  outline: none;
}

.msg {
  font-size: 0.9rem;
}

.msg.error {
  color: var(--brand-danger);
}

.msg.success {
  color: #15803d;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.actions .secondary-btn,
.actions .primary-btn {
  min-width: 100px;
  padding: 0 14px;
}
</style>


