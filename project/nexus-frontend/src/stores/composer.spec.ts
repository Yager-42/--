import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const contentApiMocks = vi.hoisted(() => ({
  saveDraft: vi.fn().mockResolvedValue({ draftId: 901 }),
  syncDraft: vi.fn().mockResolvedValue({ serverVersion: 'v2', syncTime: 1710000000000 }),
  publishPost: vi.fn().mockResolvedValue({ postId: 101, attemptId: 1, versionNum: 2, status: 'PUBLISHED' }),
  schedulePost: vi.fn().mockResolvedValue({ taskId: 501, postId: 101, status: 'SCHEDULED' }),
  cancelScheduledPost: vi.fn().mockResolvedValue({ success: true }),
  fetchContentHistory: vi.fn().mockResolvedValue({
    versions: [
      {
        id: '2',
        title: '历史版本',
        contentPreview: 'preview',
        timeLabel: '2026/4/21 13:00:00'
      }
    ],
    nextCursor: 0
  }),
  rollbackPostVersion: vi.fn().mockResolvedValue({ success: true }),
  deletePost: vi.fn().mockResolvedValue({ success: true }),
  createUploadSession: vi.fn().mockResolvedValue({
    uploadUrl: 'https://upload.example',
    token: 'token-1',
    sessionId: 'session-1'
  })
}))

vi.mock('@/services/api/contentApi', () => contentApiMocks)

import { useComposerStore } from '@/stores/composer'

describe('composer store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('saves, syncs, publishes, schedules, and loads history', async () => {
    const store = useComposerStore()
    store.updateDraft({
      title: 'Draft title',
      body: 'Draft body'
    })

    await store.saveCurrentDraft()
    await store.syncCurrentDraft()
    await store.publishCurrentDraft()
    await store.scheduleCurrentPost(1710000000000)
    await store.loadHistory()

    expect(contentApiMocks.saveDraft).toHaveBeenCalled()
    expect(contentApiMocks.syncDraft).toHaveBeenCalled()
    expect(contentApiMocks.publishPost).toHaveBeenCalled()
    expect(contentApiMocks.schedulePost).toHaveBeenCalled()
    expect(store.history[0].title).toBe('历史版本')
  })

  it('creates an upload session, rolls back, cancels schedule, and deletes post', async () => {
    const store = useComposerStore()
    store.updateDraft({
      postId: 101
    })
    store.lastTaskId = 501

    await store.createImageUploadSession(4096)
    await store.rollbackToVersion(2)
    await store.cancelCurrentSchedule()
    await store.deleteCurrentPost()

    expect(contentApiMocks.createUploadSession).toHaveBeenCalled()
    expect(contentApiMocks.rollbackPostVersion).toHaveBeenCalled()
    expect(contentApiMocks.cancelScheduledPost).toHaveBeenCalled()
    expect(contentApiMocks.deletePost).toHaveBeenCalled()
  })
})
