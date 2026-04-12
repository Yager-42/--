import { beforeEach, describe, expect, test, vi } from 'vitest'

vi.mock('@/utils/http', async () => {
  const { mockRequest } = await import('@/mocks/http')

  return {
    default: {
      get: (url: string, config?: { params?: Record<string, unknown> }) =>
        mockRequest('get', url, { params: config?.params }),
      post: (
        url: string,
        data?: unknown,
        config?: { params?: Record<string, unknown> }
      ) => mockRequest('post', url, { params: config?.params, data }),
      put: (
        url: string,
        data?: unknown,
        config?: { params?: Record<string, unknown> }
      ) => mockRequest('put', url, { params: config?.params, data }),
      patch: (
        url: string,
        data?: unknown,
        config?: { params?: Record<string, unknown> }
      ) => mockRequest('patch', url, { params: config?.params, data }),
      delete: (
        url: string,
        config?: { params?: Record<string, unknown>; data?: unknown }
      ) => mockRequest('delete', url, { params: config?.params, data: config?.data })
    }
  }
})

import { fetchAuthMe, changePassword, logout } from '@/api/auth'
import {
  deleteContent,
  fetchContentHistory,
  fetchPublishAttempt,
  fetchScheduleAudit,
  publishContent,
  rollbackContent,
  saveDraft,
  scheduleContent,
  syncDraft,
  updateSchedule,
  cancelSchedule
} from '@/api/content'
import { fetchTimeline, fetchProfileTimeline } from '@/api/feed'
import {
  deleteComment,
  fetchHotComments,
  postComment,
  postReaction,
  fetchReactionState,
  pinComment
} from '@/api/interact'
import { blockUser, checkRelationState } from '@/api/relation'
import { fetchContentDetail } from '@/api/content'
import { fetchMyPrivacy, updateMyPrivacy } from '@/api/user'
import { mockRequest, resetUIMockState } from '@/mocks/http'

describe('capability backfill api wrappers', () => {
  beforeEach(() => {
    resetUIMockState()
  })

  test('maps profile feed, privacy, reaction state, hot comments, and auth me responses', async () => {
    const timeline = await fetchTimeline({ feedType: 'FOLLOWING', limit: 2 })
    const profileTimeline = await fetchProfileTimeline({ targetId: '2', limit: 5 })
    const privacyBefore = await fetchMyPrivacy()
    const privacyUpdate = await updateMyPrivacy({
      needApproval: !privacyBefore.needApproval
    })
    const privacyAfter = await fetchMyPrivacy()
    const reactionState = await fetchReactionState({
      targetId: 'post-quiet-light',
      targetType: 'POST',
      type: 'LIKE'
    })
    const hotComments = await fetchHotComments({
      postId: 'post-quiet-light',
      limit: 2,
      preloadReplyLimit: 1
    })
    const me = await fetchAuthMe()

    expect(timeline.page).toMatchObject({
      nextCursor: expect.anything(),
      hasMore: expect.any(Boolean)
    })
    expect(Object.keys(profileTimeline).sort()).toEqual(Object.keys(timeline).sort())
    expect(profileTimeline.items.length).toBeGreaterThan(0)
    expect(profileTimeline.items.every((item) => item.authorId === '2')).toBe(true)
    expect(profileTimeline.items[0]).toMatchObject({
      id: expect.any(String),
      postId: expect.any(String),
      authorId: '2',
      author: expect.any(String),
      authorAvatar: expect.any(String),
      title: expect.any(String),
      body: expect.any(String),
      image: expect.any(String),
      mediaUrls: expect.any(Array),
      createTime: expect.any(Number),
      reactionCount: expect.any(Number),
      commentCount: expect.any(Number),
      isLiked: expect.any(Boolean)
    })
    expect(profileTimeline.page).toMatchObject({
      nextCursor: null,
      hasMore: false
    })
    expect(Object.keys(profileTimeline.page).sort()).toEqual(Object.keys(timeline.page).sort())
    expect(privacyUpdate.success).toBe(true)
    expect(privacyAfter.needApproval).toBe(!privacyBefore.needApproval)
    expect(reactionState).toMatchObject({
      state: true,
      currentCount: expect.any(Number)
    })
    expect(hotComments.pinned).toMatchObject({
      commentId: expect.any(String),
      repliesPreview: expect.any(Array)
    })
    expect(me).toMatchObject({
      userId: '1',
      phone: expect.any(String),
      nickname: expect.any(String),
      avatarUrl: expect.any(String)
    })
  })

  test('preserves draft identity through publish, history, schedule, rollback, and deletion', async () => {
    const draft = await saveDraft({
      title: 'Mock draft',
      contentText: 'Draft body',
      mediaIds: ['media-a']
    })
    const sync = await syncDraft(draft.draftId, {
      draftId: draft.draftId,
      title: 'Mock draft v2',
      diffContent: 'Draft body revised',
      clientVersion: 1,
      deviceId: 'device-a',
      mediaIds: ['media-b']
    })
    const publish = await publishContent({
      postId: draft.draftId,
      title: 'Mock draft v2',
      text: 'Draft body revised',
      mediaInfo: JSON.stringify(['media-b']),
      visibility: 'PUBLIC'
    })
    const attempt = await fetchPublishAttempt(publish.attemptId)
    const scheduled = await scheduleContent({
      postId: draft.draftId,
      publishTime: 1893456000000,
      timezone: 'Asia/Hong_Kong'
    })
    const updated = await updateSchedule({
      taskId: scheduled.taskId,
      userId: '1',
      publishTime: 1893459600000,
      contentData: JSON.stringify({ postId: draft.draftId, title: 'Mock draft v2' }),
      reason: 'reschedule'
    })
    const audit = await fetchScheduleAudit(scheduled.taskId)
    const history = await fetchContentHistory(draft.draftId, {
      userId: '1',
      limit: 10,
      offset: 0
    })
    const rollback = await rollbackContent(draft.draftId, {
      postId: draft.draftId,
      userId: '1',
      targetVersionId: history.items[0].versionId
    })
    const canceled = await cancelSchedule({
      taskId: scheduled.taskId,
      userId: '1',
      reason: 'cancel'
    })
    const auditAfterCancel = await fetchScheduleAudit(scheduled.taskId)
    const deleted = await deleteContent(draft.draftId, {
      userId: '1',
      postId: draft.draftId
    })

    expect(sync).toMatchObject({
      serverVersion: expect.any(String),
      syncTime: expect.any(Number)
    })
    expect(publish.postId).toBe(draft.draftId)
    expect(attempt.postId).toBe(draft.draftId)
    expect(scheduled.postId).toBe(draft.draftId)
    expect(updated.success).toBe(true)
    expect(updated).not.toHaveProperty('taskId')
    expect(updated).not.toHaveProperty('postId')
    expect(audit.taskId).toBe(scheduled.taskId)
    expect(audit.contentData).toContain(draft.draftId)
    expect(history.items[0]).toMatchObject({
      versionId: expect.any(String),
      title: expect.any(String),
      content: expect.any(String)
    })
    expect(rollback).toMatchObject({
      success: true,
      id: draft.draftId
    })
    expect(canceled.success).toBe(true)
    expect(auditAfterCancel.isCanceled).toBe(1)
    expect(deleted.success).toBe(true)
    await expect(mockRequest('get', `/content/${draft.draftId}`)).rejects.toThrow(/No UI mock content/)
  })

  test('relation state no longer falls back to the default session after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)
    await expect(checkRelationState(['4'])).rejects.toThrow(/auth/i)
  })

  test('reaction mutations require auth after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)

    await expect(
      postReaction({
        requestId: 'req-logout',
        targetId: 'post-quiet-light',
        targetType: 'POST',
        type: 'LIKE',
        action: 'ADD'
      })
    ).rejects.toThrow(/auth/i)
  })

  test('comment mutations require auth after logout and do not persist null authors', async () => {
    await expect(logout()).resolves.toBe(undefined)

    await expect(
      postComment({
        postId: 'post-quiet-light',
        content: 'This should not be stored'
      })
    ).rejects.toThrow(/auth/i)

    const comments = await mockRequest<{
      items: Array<{ root: { content: string; userId: string } }>
    }>('get', '/comment/list', {
      params: { postId: 'post-quiet-light', limit: 20, preloadReplyLimit: 2 }
    })

    expect(
      comments.items.some(
        (item) => item.root.content === 'This should not be stored' || item.root.userId === 'null'
      )
    ).toBe(false)
  })

  test('comment pin requires auth after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)
    await expect(
      pinComment({
        postId: 'post-quiet-light',
        commentId: 'c-root-2'
      })
    ).rejects.toThrow(/auth/i)
  })

  test('comment delete requires auth after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)
    await expect(deleteComment('c-root-2')).rejects.toThrow(/auth/i)
  })

  test('republishing an existing post preserves engagement state and omitted fields', async () => {
    const before = await fetchContentDetail('post-quiet-light')

    const publish = await publishContent({
      postId: 'post-quiet-light',
      title: 'The Architecture of Quiet Light, Revised',
      text: 'Updated body copy for the wrapper test.',
      mediaInfo: JSON.stringify(['asset-revision']),
      visibility: 'PUBLIC'
    })
    const after = await fetchContentDetail('post-quiet-light')
    const reactionState = await fetchReactionState({
      targetId: 'post-quiet-light',
      targetType: 'POST',
      type: 'LIKE'
    })

    expect(publish.postId).toBe('post-quiet-light')
    expect(after.authorId).toBe(before.authorId)
    expect(after.locationInfo).toBe(before.locationInfo)
    expect(after.likeCount).toBe(before.likeCount)
    expect(after.versionNum).toBe(before.versionNum + 1)
    expect(after.title).toBe('The Architecture of Quiet Light, Revised')
    expect(after.content).toBe('Updated body copy for the wrapper test.')
    expect(reactionState).toEqual({
      state: true,
      currentCount: before.likeCount
    })
  })

  test('republishing an existing post without mediaInfo preserves prior media state', async () => {
    const before = await fetchContentDetail('post-quiet-light')

    await publishContent({
      postId: 'post-quiet-light',
      title: 'Media-preserving update',
      text: 'Republished without media payload.',
      visibility: 'PUBLIC'
    })

    const after = await fetchContentDetail('post-quiet-light')

    expect(after.mediaUrls).toEqual(before.mediaUrls)
    expect(after.likeCount).toBe(before.likeCount)
    expect(after.versionNum).toBe(before.versionNum + 1)
  })

  test('republish of an existing post requires auth after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)

    await expect(
      publishContent({
        postId: 'post-quiet-light',
        title: 'Logged-out republish',
        text: 'This should fail.',
        visibility: 'PUBLIC'
      })
    ).rejects.toThrow(/auth/i)
  })

  test('schedule updates require auth after logout', async () => {
    const scheduled = await scheduleContent({
      postId: 'post-quiet-light',
      publishTime: 1893456000000,
      timezone: 'Asia/Hong_Kong'
    })

    await expect(logout()).resolves.toBe(undefined)

    await expect(
      updateSchedule({
        taskId: scheduled.taskId,
        publishTime: 1893459600000
      })
    ).rejects.toThrow(/auth/i)
  })

  test('schedule cancel requires auth after logout', async () => {
    const scheduled = await scheduleContent({
      postId: 'post-quiet-light',
      publishTime: 1893456000000,
      timezone: 'Asia/Hong_Kong'
    })

    await expect(logout()).resolves.toBe(undefined)

    await expect(
      cancelSchedule({
        taskId: scheduled.taskId
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rollback requires auth after logout', async () => {
    const history = await fetchContentHistory('post-quiet-light', {
      limit: 10,
      offset: 0
    })

    await expect(logout()).resolves.toBe(undefined)

    await expect(
      rollbackContent('post-quiet-light', {
        targetVersionId: history.items[0].versionId
      })
    ).rejects.toThrow(/auth/i)
  })

  test('content delete requires auth after logout', async () => {
    await expect(logout()).resolves.toBe(undefined)
    await expect(deleteContent('post-quiet-light')).rejects.toThrow(/auth/i)
  })

  test('supports comment actions, block state, password change, and logout lifecycle', async () => {
    const pinned = await pinComment({
      postId: 'post-quiet-light',
      commentId: 'c-root-2'
    })
    const deletedComment = await deleteComment('c-root-2')
    const blocked = await blockUser({
      sourceId: '1',
      targetId: '4'
    })
    const relationState = await mockRequest<{
      followingUserIds: string[]
      blockedUserIds: string[]
    }>('post', '/relation/state/batch', {
      data: { targetUserIds: ['4'] }
    })

    await expect(changePassword({ oldPassword: 'old-pass', newPassword: 'new-pass' })).resolves.toBe(
      undefined
    )
    await expect(logout()).resolves.toBe(undefined)
    await expect(fetchAuthMe()).rejects.toThrow(/auth/i)

    expect(pinned.success).toBe(true)
    expect(deletedComment.success).toBe(true)
    expect(blocked.success).toBe(true)
    expect(relationState.blockedUserIds).toContain('4')
  })
})
