<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useComposerStore } from '@/stores/composer'

const route = useRoute()
const composerStore = useComposerStore()

const scheduleTime = ref('2026-04-21T21:00')
const statusMessage = ref('')

const title = computed({
  get: () => composerStore.draft.title,
  set: (value: string) => composerStore.updateDraft({ title: value })
})

const body = computed({
  get: () => composerStore.draft.body,
  set: (value: string) => composerStore.updateDraft({ body: value })
})

async function handleSaveDraft() {
  await composerStore.saveCurrentDraft()
  statusMessage.value = '草稿已保存'
}

async function handleSyncDraft() {
  await composerStore.syncCurrentDraft()
  statusMessage.value = '草稿已同步'
}

async function handlePublish() {
  await composerStore.publishCurrentDraft()
  statusMessage.value = '内容已发布'
}

async function handleSchedule() {
  await composerStore.scheduleCurrentPost(new Date(scheduleTime.value).getTime())
  statusMessage.value = '已创建定时任务'
}

async function handleUpdateSchedule() {
  await composerStore.updateCurrentSchedule(new Date(scheduleTime.value).getTime())
  statusMessage.value = '已更新定时任务'
}

async function handleCancelSchedule() {
  await composerStore.cancelCurrentSchedule()
  statusMessage.value = '已取消定时任务'
}

async function handleLoadPublishAttempt() {
  await composerStore.loadPublishAttempt()
  statusMessage.value = '已查询发布尝试'
}

async function handleLoadScheduleAudit() {
  await composerStore.loadScheduleAudit()
  statusMessage.value = '已查询定时任务审计'
}

async function handleLoadHistory() {
  await composerStore.loadHistory()
  statusMessage.value = '已加载版本历史'
}

async function handleRollback(versionId: number) {
  await composerStore.rollbackToVersion(versionId)
  statusMessage.value = '已回滚到历史版本'
}

async function handleDelete() {
  await composerStore.deleteCurrentPost()
  statusMessage.value = '内容已删除'
}

async function handleCreateUploadSession() {
  await composerStore.createImageUploadSession(4096)
  statusMessage.value = '已生成上传会话'
}

onMounted(async () => {
  const postId = typeof route.query.postId === 'string' ? route.query.postId : ''

  if (postId) {
    await composerStore.hydrateFromPost(postId)
  }
})
</script>

<template>
  <section class="space-y-5">
    <header class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Create</p>
      <h1 class="mt-2 font-headline text-3xl font-semibold text-nx-text">创作工作台</h1>
      <p class="mt-3 max-w-2xl text-sm leading-6 text-nx-text-muted">
        在这里继续完成草稿、发布、定时发布、审计查询和版本回滚。
      </p>
      <p v-if="statusMessage" class="mt-4 text-sm font-medium text-nx-primary">{{ statusMessage }}</p>
    </header>

    <section class="grid gap-4 lg:grid-cols-[minmax(0,1.7fr)_minmax(20rem,1fr)]">
      <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Draft</p>
        <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">从一句摘要开始</h2>

        <div class="mt-5 space-y-4">
          <input
            v-model="title"
            class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            placeholder="写一个能概括内容的标题"
          />

          <textarea
            v-model="body"
            class="min-h-72 w-full rounded-[1.5rem] border border-nx-border bg-white px-4 py-4 text-sm leading-7 text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            placeholder="先写下这次想发布的内容骨架。"
          />
        </div>
      </article>

      <article class="space-y-4">
        <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Actions</p>
          <div class="mt-4 grid gap-3">
            <button data-test="save-draft" type="button" class="flex min-h-11 items-center justify-center rounded-full bg-nx-primary px-5 text-sm font-semibold text-white" @click="handleSaveDraft">
              保存草稿
            </button>
            <button data-test="sync-draft" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleSyncDraft">
              同步草稿
            </button>
            <button data-test="publish-post" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handlePublish">
              立即发布
            </button>
            <button data-test="create-upload-session" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleCreateUploadSession">
              生成上传会话
            </button>
          </div>
        </section>

        <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Schedule</p>
          <input
            v-model="scheduleTime"
            type="datetime-local"
            class="mt-4 h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
          />
          <div class="mt-4 grid gap-3">
            <button data-test="schedule-post" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleSchedule">
              创建定时
            </button>
            <button data-test="update-schedule" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleUpdateSchedule">
              更新定时
            </button>
            <button data-test="cancel-schedule" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-red-200 px-5 text-sm font-semibold text-red-600" @click="handleCancelSchedule">
              取消定时
            </button>
          </div>
        </section>

        <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
          <div class="flex items-center justify-between gap-4">
            <div>
              <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Observability</p>
              <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">发布与定时查询</h2>
            </div>
          </div>

          <div class="mt-4 grid gap-3">
            <button data-test="load-publish-attempt" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleLoadPublishAttempt">
              查询发布尝试
            </button>
            <button data-test="load-schedule-audit" type="button" class="flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text" @click="handleLoadScheduleAudit">
              查询定时审计
            </button>
          </div>

          <div class="mt-4 space-y-3">
            <article v-if="composerStore.publishAttempt" class="rounded-3xl border border-nx-border bg-nx-surface-muted p-4">
              <p class="text-xs font-medium uppercase tracking-[0.18em] text-nx-text-muted">Publish Attempt</p>
              <p class="mt-2 text-sm font-semibold text-nx-text">尝试 #{{ composerStore.publishAttempt.attemptId }}</p>
              <p class="mt-2 text-sm leading-6 text-nx-text-muted">
                状态 {{ composerStore.publishAttempt.attemptStatus }} / 风险 {{ composerStore.publishAttempt.riskStatus }}
              </p>
            </article>

            <article v-if="composerStore.scheduleAudit" class="rounded-3xl border border-nx-border bg-nx-surface-muted p-4">
              <p class="text-xs font-medium uppercase tracking-[0.18em] text-nx-text-muted">Schedule Audit</p>
              <p class="mt-2 text-sm font-semibold text-nx-text">任务 #{{ composerStore.scheduleAudit.taskId }}</p>
              <p class="mt-2 text-sm leading-6 text-nx-text-muted">
                状态 {{ composerStore.scheduleAudit.status }}，重试 {{ composerStore.scheduleAudit.retryCount }} 次
              </p>
            </article>
          </div>
        </section>

        <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
          <div class="flex items-center justify-between gap-4">
            <div>
              <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">History</p>
              <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">版本记录</h2>
            </div>
            <button data-test="load-history" type="button" class="min-h-11 rounded-full border border-nx-border px-4 text-sm font-medium text-nx-text" @click="handleLoadHistory">
              加载历史
            </button>
          </div>

          <div class="mt-4 space-y-3">
            <article
              v-for="item in composerStore.history"
              :key="item.id"
              class="rounded-3xl border border-nx-border bg-nx-surface-muted p-4"
            >
              <p class="text-sm font-semibold text-nx-text">{{ item.title }}</p>
              <p class="mt-2 text-sm leading-6 text-nx-text-muted">{{ item.contentPreview }}</p>
              <div class="mt-3 flex items-center justify-between gap-3">
                <span class="text-xs text-nx-text-muted">{{ item.timeLabel }}</span>
                <button data-test="rollback-version" type="button" class="text-xs font-semibold text-nx-primary" @click="handleRollback(Number(item.id))">
                  回滚到此版本
                </button>
              </div>
            </article>
          </div>
        </section>

        <section class="rounded-[var(--nx-radius-card)] border border-red-200 bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-red-500">Destructive</p>
          <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">删除当前内容</h2>
          <button data-test="delete-post" type="button" class="mt-4 flex min-h-11 w-full items-center justify-center rounded-full border border-red-200 px-5 text-sm font-semibold text-red-600" @click="handleDelete">
            删除内容
          </button>
        </section>
      </article>
    </section>
  </section>
</template>
