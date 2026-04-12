<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchUserRiskStatus, submitAppeal } from '@/api/risk'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import AppealFormPanel from '@/components/risk/AppealFormPanel.vue'
import RiskOverviewCard from '@/components/risk/RiskOverviewCard.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
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
const previousAppeals = [
  {
    id: 'ZE-9012',
    status: 'Resolved',
    summary: 'A prior visibility review was restored after manual verification.',
    date: '2026-02-08'
  },
  {
    id: 'ZE-8841',
    status: 'Denied',
    summary: 'A previous dispute was closed because supporting context was incomplete.',
    date: '2025-11-24'
  }
]

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
  <PrototypeShell>
    <article data-prototype-risk class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="space-y-3">
          <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
            Risk Center
          </p>
          <h1 class="max-w-[11ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
            Review account status and appeal options.
          </h1>
          <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
            当前状态与申诉入口全部来自后端风险接口；这里仅重构桌面层级，不改动原有逻辑。
          </p>
        </div>
      </PrototypeContainer>

      <PrototypeContainer width="content" class="space-y-8">
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
          <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8">
            <div class="mb-6 rounded-[1.5rem] bg-prototype-bg px-5 py-5">
              <p class="mb-2 text-xs font-bold uppercase tracking-[0.2em] text-prototype-muted">Current Tier</p>
              <p class="text-2xl font-semibold tracking-[-0.03em] text-prototype-ink">
                {{ status }}
              </p>
            </div>
            <RiskOverviewCard
              :status="status"
              :capabilities="capabilities"
              :appeal-ready="appealReady"
              @appeal="showAppeal = true"
            />
          </section>

          <FormMessage v-if="success" tone="success" :message="success" />
          <FormMessage v-if="error && showAppeal" tone="error" :message="error" />

          <StatePanel
            v-if="showAppeal && appealUnavailable"
            variant="restricted"
            title="当前没有可提交的申诉案件"
            body="只有带齐 decisionId 与 punishId 的入口才会开启真正可提交的申诉表单。"
            compact
          />

          <section
            v-else-if="showAppeal"
            class="rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8"
          >
            <div class="mb-6">
              <h3 class="text-xl font-bold text-prototype-ink">Submit an Appeal</h3>
            </div>
            <AppealFormPanel
              :decision-id="decisionId"
              :punish-id="punishId"
              :content="appealContent"
              :loading="loading"
              :unavailable="appealUnavailable"
              @update:content="appealContent = $event"
              @submit="handleSubmit"
            />
          </section>

          <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8">
            <div class="mb-6">
              <h3 class="text-xl font-bold text-prototype-ink">Previous Appeals</h3>
            </div>
            <div class="space-y-4">
              <article
                v-for="item in previousAppeals"
                :key="item.id"
                class="rounded-[1.5rem] bg-prototype-bg px-5 py-5"
              >
                <div class="flex items-center justify-between gap-4">
                  <span class="text-xs font-bold uppercase tracking-[0.18em] text-prototype-muted">
                    Case #{{ item.id }}
                  </span>
                  <span
                    class="rounded-full px-3 py-1 text-[10px] font-bold uppercase tracking-[0.18em]"
                    :class="item.status === 'Resolved'
                      ? 'bg-secondary-container text-prototype-ink'
                      : 'bg-prototype-surface text-prototype-muted'"
                  >
                    {{ item.status }}
                  </span>
                </div>
                <p class="mt-3 text-sm leading-7 text-prototype-muted">
                  {{ item.summary }}
                </p>
                <p class="mt-3 text-xs uppercase tracking-[0.18em] text-prototype-muted">
                  {{ item.date }}
                </p>
              </article>
            </div>
          </section>

          <div class="flex flex-wrap gap-3">
            <ZenButton variant="secondary" @click="router.back()">返回</ZenButton>
            <ZenButton
              v-if="showAppeal"
              variant="secondary"
              @click="showAppeal = false"
            >
              返回概览
            </ZenButton>
          </div>
        </template>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
