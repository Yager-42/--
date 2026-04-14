import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import Publish from '@/views/Publish.vue'
import { useAuthStore } from '@/store/auth'

const {
  createUploadSession,
  saveDraft,
  syncDraft,
  publishContent,
  fetchPublishAttempt,
  scheduleContent,
  updateSchedule,
  cancelSchedule,
  fetchScheduleAudit,
  fetchContentHistory,
  rollbackContent
} = vi.hoisted(() => ({
  createUploadSession: vi.fn(),
  saveDraft: vi.fn(),
  syncDraft: vi.fn(),
  publishContent: vi.fn(),
  fetchPublishAttempt: vi.fn(),
  scheduleContent: vi.fn(),
  updateSchedule: vi.fn(),
  cancelSchedule: vi.fn(),
  fetchScheduleAudit: vi.fn(),
  fetchContentHistory: vi.fn(),
  rollbackContent: vi.fn()
}))

vi.mock('@/api/content', async () => {
  const actual = await vi.importActual<typeof import('@/api/content')>('@/api/content')
  return {
    ...actual,
    createUploadSession,
    saveDraft,
    syncDraft,
    publishContent,
    fetchPublishAttempt,
    scheduleContent,
    updateSchedule,
    cancelSchedule,
    fetchScheduleAudit,
    fetchContentHistory,
    rollbackContent
  }
})

const createTestRouter = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div>Home</div>' }) },
      { path: '/publish', component: Publish }
    ]
  })

  await router.push('/publish')
  await router.isReady()
  return router
}

const mountPublish = async () => {
  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setToken('session-token', '1', 'refresh-token')

  const router = await createTestRouter()
  const wrapper = mount(Publish, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router],
      stubs: {
        PrototypeShell: { template: '<div><slot /></div>' },
        PrototypeContainer: { template: '<div><slot /></div>' },
        PublishWorkspace: {
          props: ['title', 'content', 'previews', 'loading'],
          emits: ['update:title', 'update:content', 'pick-file', 'remove-media', 'publish'],
          template: `
            <div>
              <input
                data-publish-title
                :value="title"
                @input="$emit('update:title', $event.target.value)"
              />
              <textarea
                data-publish-content
                :value="content"
                @input="$emit('update:content', $event.target.value)"
              />
              <button data-publish-submit @click="$emit('publish')">Publish</button>
            </div>
          `
        },
        ZenButton: {
          props: ['disabled', 'type'],
          emits: ['click'],
          template:
            '<button :disabled="disabled" :type="type || \'button\'" @click="$emit(\'click\')"><slot /></button>'
        }
      }
    }
  })

  await flushPromises()
  await flushPromises()

  return { wrapper, router, authStore }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  localStorage.clear()

  createUploadSession.mockResolvedValue({
    uploadUrl: 'https://example.com/upload',
    token: 'upload-token',
    sessionId: 'upload-session-1'
  })
  saveDraft.mockResolvedValue({ draftId: 'draft-100' })
  syncDraft.mockResolvedValue({
    serverVersion: '2',
    syncTime: 1710000000000
  })
  publishContent.mockResolvedValue({
    postId: 'draft-100',
    attemptId: 'attempt-200',
    versionNum: 1,
    status: 'PUBLISHING'
  })
  fetchPublishAttempt.mockResolvedValue({
    attemptId: 'attempt-200',
    postId: 'draft-100',
    userId: '1',
    idempotentToken: 'idem-1',
    transcodeJobId: 'job-1',
    attemptStatus: 1,
    riskStatus: 0,
    transcodeStatus: 1,
    publishedVersionNum: 1,
    errorCode: '',
    errorMessage: '',
    createTime: 1710000000000,
    updateTime: 1710000005000
  })
  scheduleContent.mockResolvedValue({
    taskId: 'task-300',
    postId: 'draft-100',
    status: 'SCHEDULED'
  })
  updateSchedule.mockResolvedValue({ success: true })
  cancelSchedule.mockResolvedValue({ success: true })
  fetchScheduleAudit.mockResolvedValue({
    taskId: 'task-300',
    userId: '1',
    scheduleTime: 1710003600000,
    status: 1,
    retryCount: 0,
    isCanceled: 0,
    lastError: '',
    alarmSent: 0,
    contentData: 'Body copy'
  })
  fetchContentHistory.mockResolvedValue({
    items: [
      {
        versionId: 'version-1',
        title: 'Earlier Draft',
        content: 'Recovered body',
        time: 1710000000000
      }
    ],
    page: {
      nextCursor: null,
      hasMore: false
    }
  })
  rollbackContent.mockResolvedValue({ success: true })
})

describe('publish capability backfill', () => {
  test('shows publish attempt status after publish', async () => {
    const { wrapper } = await mountPublish()

    await wrapper.get('[data-publish-title]').setValue('Quiet Light')
    await wrapper.get('[data-publish-content]').setValue('Body copy')
    await wrapper.get('[data-publish-submit]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Publishing')
    expect(wrapper.text()).toContain('Attempt ID')
  })

  test('stores draftId first, then postId and attemptId after publish', async () => {
    const { wrapper } = await mountPublish()

    await wrapper.get('[data-publish-title]').setValue('Quiet Light')
    await wrapper.get('[data-publish-content]').setValue('Body copy')
    await wrapper.get('[data-publish-submit]').trigger('click')
    await flushPromises()

    expect(saveDraft).toHaveBeenCalled()
    expect(publishContent).toHaveBeenCalledWith(
      expect.objectContaining({
        postId: 'draft-100'
      })
    )
    expect(wrapper.text()).toContain('Draft saved')
    expect(wrapper.text()).toContain('draft-100')
    expect(wrapper.text()).toContain('attempt-200')
  })

  test('lets the user schedule, reschedule, and cancel publication', async () => {
    const { wrapper } = await mountPublish()

    await wrapper.get('[data-publish-title]').setValue('Quiet Light')
    await wrapper.get('[data-publish-content]').setValue('Body copy')
    await wrapper.get('[data-publish-submit]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[aria-label="Schedule for later"]').exists()).toBe(true)

    await wrapper.get('[aria-label="Schedule for later"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('Scheduled publication')

    const scheduleSubmit = document.body.querySelector('[data-schedule-submit]') as HTMLButtonElement | null
    expect(scheduleSubmit).not.toBeNull()
    scheduleSubmit?.click()
    await flushPromises()

    expect(scheduleContent).toHaveBeenCalled()

    scheduleSubmit?.click()
    await flushPromises()

    expect(updateSchedule).toHaveBeenCalled()

    const scheduleCancel = document.body.querySelector('[data-schedule-cancel]') as HTMLButtonElement | null
    expect(scheduleCancel).not.toBeNull()
    scheduleCancel?.click()
    await flushPromises()

    expect(cancelSchedule).toHaveBeenCalledWith({
      taskId: 'task-300',
      userId: '1',
      reason: 'user cancel'
    })
  })

  test('opens version history and restores a prior version', async () => {
    const { wrapper } = await mountPublish()

    const historyButtonBeforePublish = wrapper.get('[data-open-history="true"]')
    expect(historyButtonBeforePublish.attributes('disabled')).toBeDefined()

    await wrapper.get('[data-publish-title]').setValue('Quiet Light')
    await wrapper.get('[data-publish-content]').setValue('Body copy')
    await wrapper.get('[data-publish-submit]').trigger('click')
    await flushPromises()

    const historyButton = wrapper.get('[data-open-history="true"]')
    expect(historyButton.attributes('disabled')).toBeUndefined()

    await historyButton.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('Version history')
    expect(document.body.textContent).toContain('Earlier Draft')

    const restoreButton = document.body.querySelector('[data-history-restore="version-1"]') as HTMLButtonElement | null
    expect(restoreButton).not.toBeNull()
    restoreButton?.click()
    await flushPromises()

    expect(rollbackContent).toHaveBeenCalledWith('draft-100', {
      targetVersionId: 'version-1'
    })
    expect((wrapper.get('[data-publish-title]').element as HTMLInputElement).value).toBe('Earlier Draft')
    expect((wrapper.get('[data-publish-content]').element as HTMLTextAreaElement).value).toBe('Recovered body')
  })
})
