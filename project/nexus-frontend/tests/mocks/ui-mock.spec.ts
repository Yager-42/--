import { describe, expect, test } from 'vitest'
import { mockRequest } from '@/mocks/http'

describe('ui mock transport', () => {
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

  test('throws for unsupported mock endpoints', async () => {
    await expect(mockRequest('get', '/unknown/mock-endpoint')).rejects.toThrow(
      /No UI mock handler/
    )
  })
})
