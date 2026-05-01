import { mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, test, vi } from 'vitest'
import { createAppRouter } from '@/router'
import ComposerView from '@/views/ComposerView.vue'

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => ({
      query: {}
    })
  }
})

const composerStoreMocks = vi.hoisted(() => ({
  saveCurrentDraft: vi.fn().mockResolvedValue({ draftId: 901 }),
  syncCurrentDraft: vi.fn().mockResolvedValue({ serverVersion: 'v2' }),
  publishCurrentDraft: vi.fn().mockResolvedValue({ postId: 101, attemptId: 9 }),
  scheduleCurrentPost: vi.fn().mockResolvedValue({ taskId: 501 }),
  updateCurrentSchedule: vi.fn().mockResolvedValue({ success: true }),
  cancelCurrentSchedule: vi.fn().mockResolvedValue({ success: true }),
  loadPublishAttempt: vi.fn().mockResolvedValue({ attemptId: 9, attemptStatus: 'SUCCESS' }),
  loadScheduleAudit: vi.fn().mockResolvedValue({ taskId: 501, status: 'SCHEDULED' }),
  loadHistory: vi.fn().mockResolvedValue({
    versions: [
      {
        id: '2',
        title: '上一个版本',
        contentPreview: 'preview',
        timeLabel: '2026/4/21 13:00:00'
      }
    ],
    nextCursor: 0
  }),
  rollbackToVersion: vi.fn().mockResolvedValue({ success: true }),
  deleteCurrentPost: vi.fn().mockResolvedValue({ success: true }),
  createImageUploadSession: vi.fn().mockResolvedValue({
    sessionId: 'session-1'
  })
}))

vi.mock('@/stores/composer', () => ({
  useComposerStore: () => ({
    draft: {
      title: '',
      body: '',
      postId: 101
    },
    history: [
      {
        id: '2',
        title: '上一个版本',
        contentPreview: 'preview',
        timeLabel: '2026/4/21 13:00:00'
      }
    ],
    publishAttempt: {
      attemptId: 9,
      attemptStatus: 'SUCCESS',
      riskStatus: 'LOW'
    },
    scheduleAudit: {
      taskId: 501,
      status: 'SCHEDULED',
      retryCount: 1
    },
    updateDraft: vi.fn(),
    ...composerStoreMocks
  })
}))

describe('ComposerView', () => {
  test('registers the composer route in the app router', () => {
    const router = createAppRouter()
    const routeNames = router.getRoutes().map((route) => route.name)

    expect(routeNames).toContain('compose-editor')
  })

  test('renders a creation workspace shell', () => {
    const wrapper = mount(ComposerView, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })

    expect(wrapper.text()).toContain('Create')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.text()).toContain('创作工作台')
  })

  test('triggers draft, publish, schedule, audit, rollback, delete, and upload actions', async () => {
    const wrapper = mount(ComposerView, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })

    await wrapper.get('[data-test=save-draft]').trigger('click')
    await wrapper.get('[data-test=sync-draft]').trigger('click')
    await wrapper.get('[data-test=publish-post]').trigger('click')
    await wrapper.get('[data-test=schedule-post]').trigger('click')
    await wrapper.get('[data-test=update-schedule]').trigger('click')
    await wrapper.get('[data-test=cancel-schedule]').trigger('click')
    await wrapper.get('[data-test=load-publish-attempt]').trigger('click')
    await wrapper.get('[data-test=load-schedule-audit]').trigger('click')
    await wrapper.get('[data-test=load-history]').trigger('click')
    await wrapper.get('[data-test=rollback-version]').trigger('click')
    await wrapper.get('[data-test=delete-post]').trigger('click')
    await wrapper.get('[data-test=create-upload-session]').trigger('click')

    expect(composerStoreMocks.saveCurrentDraft).toHaveBeenCalled()
    expect(composerStoreMocks.syncCurrentDraft).toHaveBeenCalled()
    expect(composerStoreMocks.publishCurrentDraft).toHaveBeenCalled()
    expect(composerStoreMocks.scheduleCurrentPost).toHaveBeenCalled()
    expect(composerStoreMocks.updateCurrentSchedule).toHaveBeenCalled()
    expect(composerStoreMocks.cancelCurrentSchedule).toHaveBeenCalled()
    expect(composerStoreMocks.loadPublishAttempt).toHaveBeenCalled()
    expect(composerStoreMocks.loadScheduleAudit).toHaveBeenCalled()
    expect(composerStoreMocks.loadHistory).toHaveBeenCalled()
    expect(composerStoreMocks.rollbackToVersion).toHaveBeenCalledWith(2)
    expect(composerStoreMocks.deleteCurrentPost).toHaveBeenCalled()
    expect(composerStoreMocks.createImageUploadSession).toHaveBeenCalled()
  })
})
