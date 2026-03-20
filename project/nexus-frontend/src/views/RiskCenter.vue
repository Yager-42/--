<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { submitAppeal } from '@/api/risk'
import TheNavBar from '@/components/TheNavBar.vue'

const router = useRouter()
const route = useRoute()
const appealContent = ref('')
const loading = ref(false)

const decisionId = route.query.decisionId as string || 'default'
const punishId = route.query.punishId as string || 'default'

const handleSubmit = async () => {
  if (!appealContent.value) return
  
  loading.value = true
  try {
    await submitAppeal({
      decisionId,
      punishId,
      content: appealContent.value
    })
    alert('申诉已提交，我们将尽快审核。')
    router.back()
  } catch (err) {
    console.error('Appeal failed', err)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="risk-center">
    <TheNavBar />
    
    <div class="content-wrapper">
      <h1 class="text-large-title">申诉中心</h1>
      <p class="text-body text-secondary desc">如果你认为我们的安全系统存在误判，请在下方说明理由。</p>
      
      <div class="case-info">
        <div class="info-row">
          <span>事件编号</span>
          <span class="value">{{ decisionId }}</span>
        </div>
        <div class="info-row">
          <span>处罚类型</span>
          <span class="value">账号禁言</span>
        </div>
      </div>

      <div class="input-section">
        <label class="text-headline">申诉理由</label>
        <textarea 
          v-model="appealContent" 
          placeholder="请详细描述你的申诉理由..." 
          class="apple-textarea"
        ></textarea>
      </div>

      <button 
        class="apple-btn" 
        :disabled="!appealContent || loading"
        @click="handleSubmit"
      >
        提交申诉
      </button>
    </div>
  </div>
</template>

<style scoped>
.risk-center {
  min-height: 100vh;
  padding-top: 44px;
  background-color: var(--apple-bg);
}

.content-wrapper {
  padding: 32px 24px;
}

.desc {
  margin: 12px 0 32px;
}

.case-info {
  background: #f5f5f7;
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 32px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 15px;
}

.value {
  font-weight: 600;
}

.input-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 40px;
}

.apple-textarea {
  width: 100%;
  height: 160px;
  background: white;
  border: 1px solid #d2d2d7;
  border-radius: 12px;
  padding: 16px;
  font-size: 16px;
  outline: none;
  resize: none;
}

.apple-textarea:focus {
  border-color: var(--apple-accent);
}

.apple-btn {
  width: 100%;
  height: 52px;
  background-color: var(--apple-accent);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
}

.apple-btn:disabled {
  opacity: 0.4;
}
</style>
