import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http/client'
import {
  fetchReactionState,
  reactToTarget,
  submitComment,
  pinComment
} from '@/services/api/interactionApi'

describe('interactionApi', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('submits reaction payload', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          requestId: 'req-1',
          currentCount: 18,
          success: true
        }
      }
    } as never)

    const response = await reactToTarget({
      requestId: 'req-1',
      targetId: 101,
      targetType: 'POST',
      type: 'LIKE',
      action: 'ADD'
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/interact/reaction', {
      requestId: 'req-1',
      targetId: 101,
      targetType: 'POST',
      type: 'LIKE',
      action: 'ADD'
    })
    expect(response.currentCount).toBe(18)
  })

  it('reads reaction state', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          state: true,
          currentCount: 9
        }
      }
    } as never)

    const response = await fetchReactionState({
      targetId: 101,
      targetType: 'POST',
      type: 'LIKE'
    })

    expect(getSpy).toHaveBeenCalledWith('/api/v1/interact/reaction/state', {
      params: {
        targetId: 101,
        targetType: 'POST',
        type: 'LIKE'
      }
    })
    expect(response.state).toBe(true)
  })

  it('submits comment payload', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          commentId: 77,
          createTime: 1710000000000,
          status: 'CREATED'
        }
      }
    } as never)

    const response = await submitComment({
      postId: 101,
      content: 'hello'
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/interact/comment', {
      postId: 101,
      content: 'hello'
    })
    expect(response.commentId).toBe(77)
  })

  it('pins a comment', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          success: true,
          id: 12,
          status: 'PINNED',
          message: 'ok'
        }
      }
    } as never)

    const response = await pinComment({
      commentId: 12,
      postId: 101
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/interact/comment/pin', {
      commentId: 12,
      postId: 101
    })
    expect(response.success).toBe(true)
  })
})
