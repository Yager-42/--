<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  cancelSchedule,
  createUploadSession,
  fetchContentHistory,
  fetchPublishAttempt,
  fetchScheduleAudit,
  publishContent,
  rollbackContent,
  saveDraft,
  scheduleContent,
  syncDraft,
  updateSchedule,
  type ContentHistoryVersionDTO
} from '@/api/content'
import { useAuthStore } from '@/store/auth'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import PublishAttemptStrip from '@/components/publish/PublishAttemptStrip.vue'
import PublishHistoryDrawer from '@/components/publish/PublishHistoryDrawer.vue'
import PublishSchedulePanel from '@/components/publish/PublishSchedulePanel.vue'
import PublishWorkspace from '@/components/publish/PublishWorkspace.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import { usePublishForm } from '@/composables/usePublishForm'
import { usePublishSession } from '@/composables/usePublishSession'

const router = useRouter()
const authStore = useAuthStore()

const {
  title,
  content,
  previews,
  mediaIds,
  uploadProgress,
  uploadError,
  canPublish,
  removeMediaAt
} = usePublishForm()
const {
  session,
  attempt,
  scheduledTask,
  storeDraftId,
  storePublishResult,
  storeScheduleResult,
  clearSchedule
} = usePublishSession()

const loading = ref(false)
const error = ref('')
const savingSchedule = ref(false)
const historyOpen = ref(false)
const historyLoading = ref(false)
const restoringHistory = ref(false)
const scheduleOpen = ref(false)
const historyItems = ref<ContentHistoryVersionDTO[]>([])
const autosaveMessage = ref('')
const publishPollTimer = ref<number | null>(null)
const draftHistory = [
  { id: 'current', label: 'Current Draft', icon: 'edit_note', active: true },
  { id: 'autumn', label: 'Autumn Series', icon: 'folder_open' },
  { id: 'exhibit', label: 'Exhibition Notes', icon: 'description' },
  { id: 'fragment', label: 'Untitled Fragment', icon: 'drafts' }
]
const postCategories = ['Editorial', 'Exhibition', 'Series']
const historyDisabled = computed(() => !session.value.postId)
const scheduleDisabled = computed(() => !session.value.postId)
const lastEditedLabel = computed(() => (session.value.draftId ? 'Draft saved' : 'Last edited just now'))

const stopAttemptPolling = () => {
  if (publishPollTimer.value !== null) {
    window.clearInterval(publishPollTimer.value)
    publishPollTimer.value = null
  }
}

const pollAttempt = async (attemptId: string) => {
  const userId = ensureUserId()
  attempt.value = await fetchPublishAttempt(attemptId, userId)
}

const startAttemptPolling = (attemptId: string) => {
  stopAttemptPolling()
  publishPollTimer.value = window.setInterval(() => {
    void pollAttempt(attemptId)
  }, 4000)
}

const ensureUserId = (): string => {
  if (!authStore.userId) {
    throw new Error('请先登录后再发布内容')
  }
  return authStore.userId
}

const uploadFile = async (file: File) => {
  const session = await createUploadSession({
    fileType: file.type || 'application/octet-stream',
    fileSize: file.size
  })

  const uploadResponse = await fetch(session.uploadUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': file.type || 'application/octet-stream'
    },
    body: file
  })

  if (!uploadResponse.ok) {
    throw new Error('图片上传失败')
  }

  previews.value.push(URL.createObjectURL(file))
  mediaIds.value.push(session.sessionId)
}

const handleFileSelect = async (event: Event) => {
  const files = (event.target as HTMLInputElement).files
  if (!files || files.length === 0) return

  loading.value = true
  error.value = ''
  uploadError.value = ''
  uploadProgress.value = 15

  try {
    await uploadFile(files[0])
    uploadProgress.value = 100
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    loading.value = false
    setTimeout(() => {
      uploadProgress.value = 0
    }, 260)
  }
}

const onPublish = async () => {
  if (!content.value.trim()) {
    error.value = '请输入正文内容'
    return
  }

  loading.value = true
  error.value = ''
  try {
    const userId = ensureUserId()
    if (!session.value.draftId) {
      const draft = await saveDraft({
        userId,
        title: title.value.trim(),
        contentText: content.value.trim(),
        mediaIds: mediaIds.value
      })
      storeDraftId(draft.draftId)
    }

    const publishResult = await publishContent({
      postId: session.value.draftId || undefined,
      userId,
      title: title.value.trim(),
      text: content.value.trim(),
      mediaInfo: JSON.stringify(mediaIds.value),
      visibility: 'PUBLIC'
    })
    storePublishResult(publishResult)
    autosaveMessage.value = 'Draft saved'
    await pollAttempt(publishResult.attemptId)
    startAttemptPolling(publishResult.attemptId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发布失败'
  } finally {
    loading.value = false
  }
}

const runDraftSync = async () => {
  if (!session.value.draftId) return

  try {
    const res = await syncDraft(session.value.draftId, {
      title: title.value.trim(),
      diffContent: content.value.trim(),
      clientVersion: Date.now(),
      deviceId: 'web-desktop',
      mediaIds: mediaIds.value
    })
    autosaveMessage.value = `Synced ${new Date(res.syncTime).toLocaleTimeString('en-US', {
      hour: 'numeric',
      minute: '2-digit'
    })}`
  } catch (e) {
    console.error('draft sync failed', e)
  }
}

const openHistory = async () => {
  if (!session.value.postId) return

  historyLoading.value = true
  historyOpen.value = true
  try {
    const res = await fetchContentHistory(session.value.postId)
    historyItems.value = res.items
  } finally {
    historyLoading.value = false
  }
}

const restoreVersion = async (versionId: string) => {
  if (!session.value.postId) return

  restoringHistory.value = true
  try {
    await rollbackContent(session.value.postId, { targetVersionId: versionId })
    const selected = historyItems.value.find((item) => item.versionId === versionId)
    if (selected) {
      title.value = selected.title
      content.value = selected.content
    }

    const res = await fetchContentHistory(session.value.postId)
    historyItems.value = res.items
  } finally {
    restoringHistory.value = false
  }
}

const saveSchedule = async (payload: { publishTime: number }) => {
  if (!session.value.postId) return

  savingSchedule.value = true
  error.value = ''
  try {
    const userId = ensureUserId()

    if (session.value.taskId) {
      await updateSchedule({
        taskId: session.value.taskId,
        userId,
        publishTime: payload.publishTime,
        contentData: content.value,
        reason: 'user update'
      })
      scheduledTask.value = await fetchScheduleAudit(session.value.taskId, userId)
      storeScheduleResult(scheduledTask.value)
    } else {
      const created = await scheduleContent({
        postId: session.value.postId,
        publishTime: payload.publishTime,
        timezone: 'Asia/Hong_Kong'
      })
      storeScheduleResult(created)
      scheduledTask.value = await fetchScheduleAudit(created.taskId, userId)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '定时发布保存失败'
  } finally {
    savingSchedule.value = false
  }
}

const cancelScheduledPublish = async () => {
  if (!session.value.taskId) return

  savingSchedule.value = true
  error.value = ''
  try {
    await cancelSchedule({
      taskId: session.value.taskId,
      userId: ensureUserId(),
      reason: 'user cancel'
    })
    clearSchedule()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '取消定时发布失败'
  } finally {
    savingSchedule.value = false
  }
}

watch([title, content], () => {
  void runDraftSync()
})

onBeforeUnmount(() => {
  stopAttemptPolling()
})
</script>

<template>
  <PrototypeShell>
    <article data-prototype-publish class="space-y-10 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="flex flex-wrap items-end justify-between gap-4">
          <div class="space-y-3">
            <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
              Publish
            </p>
            <h1 class="font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
              Shape a new post before it goes live.
            </h1>
            <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
              发布页保留真实草稿与发布调用，只替换为 prototype desktop shell 的阅读式层级。
            </p>
          </div>

          <ZenButton variant="secondary" @click="router.back()">取消</ZenButton>
        </div>
      </PrototypeContainer>

      <PrototypeContainer class="grid gap-8 xl:grid-cols-[18rem,minmax(0,1fr),18rem] xl:items-start">
        <aside class="hidden xl:flex xl:sticky xl:top-24 xl:flex-col xl:gap-5 xl:rounded-[2rem] xl:border xl:border-prototype-line xl:bg-prototype-bg xl:p-6">
          <div class="space-y-2">
            <div class="flex items-center gap-3">
              <span class="material-symbols-outlined text-prototype-accent">edit_note</span>
              <h2 class="text-lg font-semibold text-prototype-ink">Draft History</h2>
            </div>
            <p class="text-xs font-medium uppercase tracking-[0.18em] text-prototype-muted">
              Local auto-saves
            </p>
          </div>

          <button
            type="button"
            class="flex min-h-[3rem] items-center justify-center gap-2 rounded-[1rem] bg-prototype-ink px-4 text-sm font-semibold text-prototype-surface transition hover:opacity-90"
          >
            <span class="material-symbols-outlined text-base">add</span>
            New Draft
          </button>

          <div class="grid gap-2">
            <button
              v-for="item in draftHistory"
              :key="item.id"
              type="button"
              class="flex items-center gap-3 rounded-[1rem] px-4 py-3 text-left text-sm transition"
              :class="item.active
                ? 'bg-prototype-surface font-semibold text-prototype-ink'
                : 'text-prototype-muted hover:bg-prototype-surface hover:text-prototype-ink'"
            >
              <span class="material-symbols-outlined text-base">{{ item.icon }}</span>
              <span>{{ item.label }}</span>
            </button>
          </div>

          <div class="mt-auto rounded-[1.5rem] bg-prototype-surface p-4">
            <p class="text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-muted">Editor Tip</p>
            <p class="mt-2 text-sm leading-6 text-prototype-muted">
              Asymmetry creates visual rhythm. Try varying your image widths.
            </p>
          </div>
        </aside>

        <section class="space-y-6">
        <PublishAttemptStrip
          :draft-id="session.draftId"
          :post-id="session.postId"
          :attempt-id="session.attemptId"
          :attempt="attempt"
        />

        <FormMessage v-if="error" tone="error" :message="error" />

        <StatePanel
          v-if="uploadError"
          variant="upload-failure"
          :body="uploadError"
          compact
          :surface="false"
        />

          <div class="space-y-6">
            <div class="space-y-4">
              <div class="flex items-center gap-3">
                <span class="rounded-full bg-prototype-surface px-3 py-1 text-[11px] font-bold uppercase tracking-[0.2em] text-prototype-muted">
                  Draft
                </span>
                <span class="text-sm text-prototype-muted">{{ autosaveMessage || lastEditedLabel }}</span>
              </div>
              <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
                Hero Exhibition
              </p>
            </div>

            <section class="relative ml-auto aspect-[16/9] w-[92%] overflow-hidden rounded-[2rem] border border-dashed border-prototype-line bg-prototype-surface shadow-[0_26px_64px_rgba(27,31,31,0.08)]">
              <div class="absolute inset-0 flex flex-col items-center justify-center gap-3 text-center">
                <span class="material-symbols-outlined text-4xl text-prototype-muted">add_a_photo</span>
                <div>
                  <p class="text-base font-semibold text-prototype-ink">Add a Hero Visual</p>
                  <p class="mt-1 text-sm text-prototype-muted">Drag and drop or click to upload curated photography</p>
                </div>
              </div>
              <div class="absolute -left-10 top-1/2 hidden -translate-y-1/2 xl:block">
                <span class="origin-left rotate-90 whitespace-nowrap text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-muted">
                  Hero Exhibition
                </span>
              </div>
            </section>
          </div>

          <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface p-6 md:p-8">
          <PublishWorkspace
            :title="title"
            :content="content"
            :previews="previews"
            :loading="loading"
            @update:title="title = $event"
            @update:content="content = $event"
            @pick-file="handleFileSelect"
            @remove-media="removeMediaAt"
            @publish="onPublish"
          />
          </section>

          <p class="text-sm leading-7 text-prototype-muted">
            当前版本只提交后端已支持的标题、正文、媒体与公开可见性，不伪造草稿历史或额外隐私设置。
          </p>
        </section>

        <aside class="hidden xl:flex xl:sticky xl:top-24 xl:flex-col xl:gap-6 xl:rounded-[2rem] xl:border xl:border-prototype-line xl:bg-prototype-surface xl:p-6">
          <div class="space-y-2">
            <h3 class="text-xs font-bold uppercase tracking-[0.2em] text-prototype-muted">Post Settings</h3>
          </div>

          <div class="space-y-3">
            <label class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">Category</label>
            <div class="flex flex-wrap gap-2">
              <span
                v-for="category in postCategories"
                :key="category"
                class="rounded-full px-3 py-1 text-[11px] font-bold uppercase tracking-[0.18em]"
                :class="category === 'Series'
                  ? 'bg-prototype-ink text-prototype-surface'
                  : 'bg-prototype-bg text-prototype-muted'"
              >
                {{ category }}
              </span>
            </div>
          </div>

          <div class="space-y-3">
            <label class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">Privacy</label>
            <div class="grid grid-cols-2 gap-2 rounded-[1rem] bg-prototype-bg p-1">
              <button type="button" class="rounded-[0.8rem] bg-prototype-surface px-3 py-2 text-xs font-semibold text-prototype-ink">
                Public
              </button>
              <button type="button" class="rounded-[0.8rem] px-3 py-2 text-xs font-semibold text-prototype-muted">
                Private
              </button>
            </div>
          </div>

          <div class="space-y-3 border-t border-prototype-line pt-4">
            <button
              type="button"
              aria-label="Schedule for later"
              class="flex min-h-[52px] w-full items-center justify-between rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 text-left text-sm font-semibold text-prototype-ink transition hover:border-prototype-ink disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="scheduleDisabled"
              @click="scheduleOpen = true"
            >
              <span>Schedule for later</span>
              <span class="text-[11px] uppercase tracking-[0.18em] text-prototype-muted">
                {{ session.taskId ? 'Active' : 'Optional' }}
              </span>
            </button>

            <button
              type="button"
              data-open-history="true"
              class="flex min-h-[52px] w-full items-center justify-between rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 text-left text-sm font-semibold text-prototype-ink transition hover:border-prototype-ink disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="historyDisabled"
              @click="openHistory"
            >
              <span>Version history</span>
              <span class="text-[11px] uppercase tracking-[0.18em] text-prototype-muted">
                {{ historyDisabled ? 'Publish first' : 'Ready' }}
              </span>
            </button>
          </div>

          <div class="space-y-4 border-t border-prototype-line pt-4">
            <div class="flex items-center justify-between">
              <span class="text-sm font-semibold text-prototype-ink">Allow Comments</span>
              <span class="relative h-6 w-11 rounded-full bg-prototype-ink/90">
                <span class="absolute right-1 top-1 h-4 w-4 rounded-full bg-white" />
              </span>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm font-semibold text-prototype-ink">Show Metadata</span>
              <span class="relative h-6 w-11 rounded-full bg-prototype-line/80">
                <span class="absolute left-1 top-1 h-4 w-4 rounded-full bg-white" />
              </span>
            </div>
          </div>
        </aside>
      </PrototypeContainer>
    </article>

    <div v-if="uploadProgress > 0" class="publish-progress" :style="{ width: `${uploadProgress}%` }" />

    <PublishSchedulePanel
      :open="scheduleOpen"
      :saving="savingSchedule"
      :task="scheduledTask"
      @close="scheduleOpen = false"
      @submit="saveSchedule"
      @cancel="cancelScheduledPublish"
    />

    <PublishHistoryDrawer
      :open="historyOpen"
      :loading="historyLoading"
      :restoring="restoringHistory"
      :items="historyItems"
      @close="historyOpen = false"
      @restore="restoreVersion"
    />
  </PrototypeShell>
</template>
<style scoped>
.publish-progress {
  position: fixed;
  left: 0;
  top: 0;
  height: 3px;
  background: var(--prototype-ink);
  transition: width 200ms ease;
  z-index: 999;
}
</style>
