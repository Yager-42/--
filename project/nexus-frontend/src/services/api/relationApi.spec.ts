import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http/client'
import {
  batchRelationState,
  fetchFollowers,
  fetchFollowing,
  followUser,
  unfollowUser
} from '@/services/api/relationApi'

describe('relationApi', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('follows a user', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          status: 'FOLLOWED'
        }
      }
    } as never)

    const response = await followUser({
      sourceId: 7,
      targetId: 8
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/relation/follow', {
      sourceId: 7,
      targetId: 8
    })
    expect(response.status).toBe('FOLLOWED')
  })

  it('unfollows a user', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          status: 'UNFOLLOWED'
        }
      }
    } as never)

    const response = await unfollowUser({
      sourceId: 7,
      targetId: 8
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/relation/unfollow', {
      sourceId: 7,
      targetId: 8
    })
    expect(response.status).toBe('UNFOLLOWED')
  })

  it('loads followers list', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              userId: 8,
              nickname: 'Alice',
              avatar: '',
              followTime: 1710000000000
            }
          ],
          nextCursor: 'next-1'
        }
      }
    } as never)

    const response = await fetchFollowers(7)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/relation/followers', {
      params: {
        userId: 7
      }
    })
    expect(response.items[0].nickname).toBe('Alice')
  })

  it('loads following list', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              userId: 9,
              nickname: 'Bob',
              avatar: '',
              followTime: 1710001000000
            }
          ],
          nextCursor: ''
        }
      }
    } as never)

    const response = await fetchFollowing(7)

    expect(response.items[0].id).toBe('9')
  })

  it('loads relation state batch', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          followingUserIds: [8],
          blockedUserIds: []
        }
      }
    } as never)

    const response = await batchRelationState({
      targetUserIds: [8, 9]
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/relation/state/batch', {
      targetUserIds: [8, 9]
    })
    expect(response.followingUserIds).toEqual([8])
  })
})
