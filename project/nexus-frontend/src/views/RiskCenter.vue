<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchUserRiskStatus, submitAppeal } from '@/api/risk'
import AppealFormPanel from '@/components/risk/AppealFormPanel.vue'
import RiskOverviewCard from '@/components/risk/RiskOverviewCard.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import { useRiskRouteMode } from '@/composables/useRiskRouteMode'

const router = useRouter()
const route = useRoute()
const { decisionId, punishId, appealReady, appealUnavailable } = useRiskRouteMode(route)

const status = ref<'NORMAL' | 'LIMITED' | 'BANNED'>('NORMAL')
const capabilities = ref<string[]>([])
const appealContent = ref('')
const loading = ref(false)
const pageLoading = ref(false)
const error = ref('')
const success = ref('')
const showAppeal = ref(false)

const loadRiskStatus = async () => {
  pageLoading.value = true
  error.value = ''
  try {
    const response = await fetchUserRiskStatus()
    status.value = response.status
    capabilities.value = response.capabilities
  } catch (e) {
    error.value = e instanceof Error ? e.message : '风险状态加载失败'
  } finally {
    pageLoading.value = false
  }
}

const handleSubmit = async () => {
  if (!appealReady.value || !appealContent.value.trim()) {
    error.value = '当前缺少可提交的申诉参数或申诉内容'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    await submitAppeal({
      decisionId: decisionId.value,
      punishId: punishId.value,
      content: appealContent.value.trim()
    })
    success.value = '申诉已提交，我们会尽快处理。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadRiskStatus()
})
</script>

<template>
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main">
      <section class="paper-panel grid gap-6 p-6 md:p-8">
        <StatePanel
          v-if="pageLoading"
          variant="loading"
          title="正在同步风险状态"
          body="我们正在读取最新的限制与申诉条件。"
        />

        <StatePanel
          v-else-if="error && !success && !showAppeal"
          variant="request-failure"
          :body="error"
          action-label="重试"
          @action="loadRiskStatus"
        />

        <template v-else>
          <RiskOverviewCard
            :status="status"
            :capabilities="capabilities"
            :appeal-ready="appealReady"
            @appeal="showAppeal = true"
          />

          <FormMessage v-if="success" tone="success" :message="success" />
          <FormMessage v-if="error && showAppeal" tone="error" :message="error" />

          <StatePanel
            v-if="showAppeal && appealUnavailable"
            variant="restricted"
            title="当前没有可提交的申诉案件"
            body="只有带齐 decisionId 与 punishId 的入口才会开启真正可提交的申诉表单。"
            compact
          />

          <AppealFormPanel
            v-else-if="showAppeal"
            :decision-id="decisionId"
            :punish-id="punishId"
            :content="appealContent"
            :loading="loading"
            :unavailable="appealUnavailable"
            @update:content="appealContent = $event"
            @submit="handleSubmit"
          />

          <div class="risk-actions">
            <button type="button" class="secondary-btn" @click="router.back()">返回</button>
            <button
              v-if="showAppeal"
              type="button"
              class="secondary-btn"
              @click="showAppeal = false"
            >
              返回概览
            </button>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<style scoped>
.risk-actions {
  display: flex;
  gap: 0.75rem;
}
</style>
