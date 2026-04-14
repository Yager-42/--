import { beforeEach, describe, expect, test } from 'vitest'
import { mockRequest, resetUIMockState } from '@/mocks/http'

describe('ui mock transport', () => {
  beforeEach(() => {
    resetUIMockState()
  })

  test('returns search results for archive queries', async () => {
    const response = await mockRequest('get', '/search', {
      params: { q: 'quiet', size: 20 }
    })

    expect(Array.isArray(response.items)).toBe(true)
    expect(response.items.length).toBeGreaterThan(0)
    expect(response.items[0]).toMatchObject({
      id: expect.any(String),
      title: expect.any(String),
      authorNickname: expect.any(String)
    })
  })

  test('returns profile page data with relation and risk payload', async () => {
    const response = await mockRequest('get', '/user/profile/page', {
      params: { targetUserId: '2' }
    })

    expect(response.profile).toMatchObject({
      userId: '2',
      nickname: expect.any(String)
    })
    expect(response.relation).toMatchObject({
      followCount: expect.any(Number),
      followerCount: expect.any(Number),
      isFollow: expect.any(Boolean)
    })
    expect(response.risk).toMatchObject({
      status: expect.any(String),
      capabilities: expect.any(Array)
    })
  })

  test('supports content detail and comment list flows', async () => {
    const detail = await mockRequest('get', '/content/post-quiet-light', {
      params: { userId: '1' }
    })
    const comments = await mockRequest('get', '/comment/list', {
      params: { postId: 'post-quiet-light', limit: 20, preloadReplyLimit: 2 }
    })

    expect(detail).toMatchObject({
      postId: 'post-quiet-light',
      authorNickname: expect.any(String),
      content: expect.any(String)
    })
    expect(comments.items.length).toBeGreaterThan(0)
    expect(comments.items[0].root.commentId).toEqual(expect.any(String))
  })

  test('supports notification reads and block state lookups', async () => {
    const before = await mockRequest<{
      notifications: Array<{ notificationId: string; unreadCount: number }>
    }>('get', '/notification/list')
    expect(before.notifications.find((item) => item.notificationId === 'n-1001')?.unreadCount).toBe(1)

    await mockRequest('post', '/notification/read', {
      data: { notificationId: 'n-1001' }
    })
    await mockRequest('post', '/relation/block', {
      data: { sourceId: '1', targetId: '4' }
    })

    const after = await mockRequest<{
      notifications: Array<{ notificationId: string; unreadCount: number }>
    }>('get', '/notification/list')
    const relationState = await mockRequest<{
      followingUserIds: string[]
      blockedUserIds: string[]
    }>('post', '/relation/state/batch', {
      data: { targetUserIds: ['4'] }
    })

    expect(after.notifications.find((item) => item.notificationId === 'n-1001')?.unreadCount).toBe(0)
    expect(relationState.blockedUserIds).toContain('4')
  })

  test('rejects relation-state lookups after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/relation/state/batch', {
        data: { targetUserIds: ['4'] }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects reaction mutations after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/interact/reaction', {
        data: {
          requestId: 'req-logout',
          targetId: 'post-quiet-light',
          targetType: 'POST',
          type: 'LIKE',
          action: 'ADD'
        }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects comment mutations after logout without storing a null userId', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/interact/comment', {
        data: {
          postId: 'post-quiet-light',
          content: 'This should not be stored'
        }
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

  test('supports hot comment pinning and deletion flows', async () => {
    const before = await mockRequest<{
      pinned: { root: { commentId: string } } | null
      items: Array<{ root: { commentId: string } }>
    }>('get', '/comment/hot', {
      params: { postId: 'post-quiet-light', limit: 2, preloadReplyLimit: 1 }
    })
    expect(before.pinned?.root.commentId).toBe('c-root-1')

    await mockRequest('post', '/interact/comment/pin', {
      data: { commentId: 'c-root-2', postId: 'post-quiet-light' }
    })

    const pinned = await mockRequest<{
      pinned: { root: { commentId: string } } | null
      items: Array<{ root: { commentId: string } }>
    }>('get', '/comment/hot', {
      params: { postId: 'post-quiet-light', limit: 2, preloadReplyLimit: 1 }
    })
    expect(pinned.pinned?.root.commentId).toBe('c-root-2')

    await mockRequest('delete', '/comment/c-root-2')

    const afterDelete = await mockRequest<{
      pinned: { root: { commentId: string } } | null
      items: Array<{ root: { commentId: string } }>
    }>('get', '/comment/hot', {
      params: { postId: 'post-quiet-light', limit: 2, preloadReplyLimit: 1 }
    })

    expect(afterDelete.pinned?.root.commentId).toBe('c-root-1')
    expect(afterDelete.items.some((item) => item.root.commentId === 'c-root-2')).toBe(false)
  })

  test('rejects comment pin after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/interact/comment/pin', {
        data: { commentId: 'c-root-2', postId: 'post-quiet-light' }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects comment delete after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(mockRequest('delete', '/comment/c-root-2')).rejects.toThrow(/auth/i)
  })

  test('supports draft publish lifecycle, schedule audit, and auth endpoints', async () => {
    const me = await mockRequest<{
      userId: string
      phone: string
      nickname: string
      avatarUrl: string
    }>('get', '/auth/me')
    expect(me).toMatchObject({
      userId: '1',
      phone: expect.any(String),
      nickname: expect.any(String),
      avatarUrl: expect.any(String)
    })

    await mockRequest('post', '/user/me/privacy', {
      data: { needApproval: true }
    })
    const privacy = await mockRequest<{ needApproval: boolean }>('get', '/user/me/privacy')
    expect(privacy.needApproval).toBe(true)

    const draft = await mockRequest<{ draftId: string }>('put', '/content/draft', {
      data: {
        title: 'Draft title',
        contentText: 'Draft body',
        mediaIds: ['asset-1']
      }
    })
    const sync = await mockRequest<{ serverVersion: string; syncTime: number }>(
      'patch',
      `/content/draft/${draft.draftId}`,
      {
        data: {
          draftId: draft.draftId,
          title: 'Draft title v2',
          diffContent: 'Draft body revised',
          clientVersion: 1,
          deviceId: 'device-a',
          mediaIds: ['asset-2']
        }
      }
    )
    expect(sync).toMatchObject({
      serverVersion: expect.any(String),
      syncTime: expect.any(Number)
    })

    const publish = await mockRequest<{
      postId: string
      attemptId: string
      versionNum: number
      status: string
    }>('post', '/content/publish', {
      data: {
        postId: draft.draftId,
        title: 'Draft title v2',
        text: 'Draft body revised',
        mediaInfo: JSON.stringify(['asset-2']),
        visibility: 'PUBLIC'
      }
    })
    expect(publish.postId).toBe(draft.draftId)

    const attempt = await mockRequest<{ postId: string; publishedVersionNum: number }>(
      'get',
      `/content/publish/attempt/${publish.attemptId}`
    )
    expect(attempt).toMatchObject({
      postId: draft.draftId,
      publishedVersionNum: publish.versionNum
    })

    const scheduled = await mockRequest<{ taskId: string; postId: string }>('post', '/content/schedule', {
      data: {
        postId: draft.draftId,
        publishTime: 1893456000000,
        timezone: 'Asia/Hong_Kong'
      }
    })
    expect(scheduled.postId).toBe(draft.draftId)

    const updated = await mockRequest<{ success: boolean }>('patch', '/content/schedule', {
      data: {
        taskId: scheduled.taskId,
        publishTime: 1893459600000,
        contentData: JSON.stringify({ postId: draft.draftId, title: 'Draft title v2' }),
        reason: 'reschedule'
      }
    })
    expect(updated).toEqual({
      success: true,
      status: expect.any(String)
    })

    const audit = await mockRequest<{
      taskId: string
      isCanceled: number
      contentData: string
    }>('get', `/content/schedule/${scheduled.taskId}`)
    expect(audit).toMatchObject({
      taskId: scheduled.taskId,
      isCanceled: 0
    })
    expect(audit.contentData).toContain(draft.draftId)

    const history = await mockRequest<{
      versions: Array<{ versionId: string; title: string; content: string }>
      nextCursor: number | null
    }>('get', `/content/${draft.draftId}/history`, {
      params: { limit: 10, offset: 0 }
    })
    expect(history.versions[0]).toMatchObject({
      versionId: expect.any(String),
      title: expect.any(String),
      content: expect.any(String)
    })

    const rollback = await mockRequest<{ success: boolean; id: string }>(
      'post',
      `/content/${draft.draftId}/rollback`,
      {
        data: {
          postId: draft.draftId,
          targetVersionId: history.versions[0].versionId
        }
      }
    )
    expect(rollback).toMatchObject({
      success: true,
      id: draft.draftId
    })

    await mockRequest('post', '/content/schedule/cancel', {
      data: { taskId: scheduled.taskId, reason: 'cancel' }
    })
    const canceledAudit = await mockRequest<{ isCanceled: number }>(
      'get',
      `/content/schedule/${scheduled.taskId}`
    )
    expect(canceledAudit.isCanceled).toBe(1)

    await mockRequest('delete', `/content/${draft.draftId}`)
    await expect(mockRequest('get', `/content/${draft.draftId}`)).rejects.toThrow(/No UI mock content/)

    await mockRequest('post', '/auth/password/change', {
      data: { oldPassword: 'old-pass', newPassword: 'new-pass' }
    })
    await mockRequest('post', '/auth/logout')
    await expect(mockRequest('get', '/auth/me')).rejects.toThrow(/auth/i)
  })

  test('republishing an existing post preserves existing engagement and omitted fields', async () => {
    const before = await mockRequest<{
      postId: string
      authorId: string
      title: string
      content: string
      locationInfo: string
      likeCount: number
      versionNum: number
    }>('get', '/content/post-quiet-light')

    const publish = await mockRequest<{
      postId: string
      attemptId: string
      versionNum: number
    }>('post', '/content/publish', {
      data: {
        postId: 'post-quiet-light',
        title: 'The Architecture of Quiet Light, Revised',
        text: 'Updated body copy for the mock transport.',
        mediaInfo: JSON.stringify(['asset-revision']),
        visibility: 'PUBLIC'
      }
    })

    const after = await mockRequest<{
      postId: string
      authorId: string
      title: string
      content: string
      locationInfo: string
      likeCount: number
      versionNum: number
    }>('get', '/content/post-quiet-light')
    const reactionState = await mockRequest<{ state: boolean; currentCount: number }>(
      'get',
      '/interact/reaction/state',
      {
        params: { targetId: 'post-quiet-light', targetType: 'POST', type: 'LIKE' }
      }
    )

    expect(publish.postId).toBe('post-quiet-light')
    expect(after.authorId).toBe(before.authorId)
    expect(after.title).toBe('The Architecture of Quiet Light, Revised')
    expect(after.content).toBe('Updated body copy for the mock transport.')
    expect(after.locationInfo).toBe(before.locationInfo)
    expect(after.likeCount).toBe(before.likeCount)
    expect(after.versionNum).toBe(before.versionNum + 1)
    expect(reactionState).toEqual({
      state: true,
      currentCount: before.likeCount
    })
  })

  test('republishing an existing post without mediaInfo preserves prior media state', async () => {
    const initial = await mockRequest<{
      mediaInfo: string
      versionNum: number
      likeCount: number
    }>('get', '/content/post-quiet-light')

    await mockRequest('post', '/content/publish', {
      data: {
        postId: 'post-quiet-light',
        title: 'Media-preserving update',
        text: 'Republished without media payload.'
      }
    })

    const after = await mockRequest<{
      mediaInfo: string
      versionNum: number
      likeCount: number
    }>('get', '/content/post-quiet-light')

    expect(after.mediaInfo).toBe(initial.mediaInfo)
    expect(after.likeCount).toBe(initial.likeCount)
    expect(after.versionNum).toBe(initial.versionNum + 1)
  })

  test('rejects republish of an existing post after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/content/publish', {
        data: {
          postId: 'post-quiet-light',
          title: 'Logged-out republish',
          text: 'This should fail.'
        }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects schedule updates after logout', async () => {
    const scheduled = await mockRequest<{ taskId: string }>('post', '/content/schedule', {
      data: {
        postId: 'post-quiet-light',
        publishTime: 1893456000000,
        timezone: 'Asia/Hong_Kong'
      }
    })

    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('patch', '/content/schedule', {
        data: {
          taskId: scheduled.taskId,
          publishTime: 1893459600000
        }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects schedule cancel after logout', async () => {
    const scheduled = await mockRequest<{ taskId: string }>('post', '/content/schedule', {
      data: {
        postId: 'post-quiet-light',
        publishTime: 1893456000000,
        timezone: 'Asia/Hong_Kong'
      }
    })

    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/content/schedule/cancel', {
        data: { taskId: scheduled.taskId }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects rollback after logout', async () => {
    const history = await mockRequest<{
      versions: Array<{ versionId: string }>
    }>('get', '/content/post-quiet-light/history', {
      params: { limit: 10, offset: 0 }
    })

    await mockRequest('post', '/auth/logout')

    await expect(
      mockRequest('post', '/content/post-quiet-light/rollback', {
        data: { targetVersionId: history.versions[0].versionId }
      })
    ).rejects.toThrow(/auth/i)
  })

  test('rejects content delete after logout', async () => {
    await mockRequest('post', '/auth/logout')

    await expect(mockRequest('delete', '/content/post-quiet-light')).rejects.toThrow(/auth/i)
  })

  test('throws for unsupported mock endpoints', async () => {
    await expect(mockRequest('get', '/unknown/mock-endpoint')).rejects.toThrow(
      /No UI mock handler/
    )
  })
})
