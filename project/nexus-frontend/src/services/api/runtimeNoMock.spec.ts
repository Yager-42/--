import { beforeEach, describe, expect, it, vi } from 'vitest'

import { http } from '@/services/http/client'
import { fetchTimeline } from '@/services/api/feedApi'
import { fetchPostDetail } from '@/services/api/contentApi'
import { fetchMyProfileViewModel } from '@/services/api/profileApi'
import { searchContent } from '@/services/api/searchApi'
import { fetchNotifications, markAllNotificationsRead } from '@/services/api/notificationApi'

describe('runtime api calls without mock mode', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('nexus.mockMode', 'true')
    vi.restoreAllMocks()
  })

  it('fetchTimeline still calls the backend when mock flag is present', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              postId: 101,
              authorId: 7,
              authorNickname: 'Author',
              authorAvatar: '',
              text: 'Body',
              summary: 'Summary',
              mediaType: 0,
              publishTime: 1710000000000,
              likeCount: 12,
              liked: false,
              followed: false,
              seen: false
            }
          ],
          nextCursor: 'cursor-2'
        }
      }
    } as never)

    const result = await fetchTimeline({ feedType: 'POPULAR' })

    expect(getSpy).toHaveBeenCalledWith('/api/v1/feed/timeline', {
      params: {
        cursor: undefined,
        limit: 20,
        feedType: 'POPULAR'
      }
    })
    expect(result.nextCursor).toBe('cursor-2')
    expect(result.items).toHaveLength(1)
  })

  it('fetchPostDetail still calls the backend when mock flag is present', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          postId: 88,
          authorId: 7,
          authorNickname: 'Author',
          authorAvatarUrl: '',
          title: 'Title',
          content: 'Body',
          summary: 'Summary',
          createTime: 1710000000000,
          likeCount: 3
        }
      }
    } as never)

    const result = await fetchPostDetail(88)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/content/88')
    expect(result.id).toBe('88')
    expect(result.title).toBe('Title')
  })

  it('fetchMyProfileViewModel still calls profile endpoints when mock flag is present', async () => {
    const getSpy = vi
      .spyOn(http, 'get')
      .mockResolvedValueOnce({
        data: {
          code: '0000',
          info: 'ok',
          data: {
            userId: 7,
            username: 'user7',
            nickname: 'Nexus User',
            avatarUrl: '',
            status: 'ACTIVE'
          }
        }
      } as never)
      .mockResolvedValueOnce({
        data: {
          code: '0000',
          info: 'ok',
          data: {
            needApproval: true
          }
        }
      } as never)

    const result = await fetchMyProfileViewModel()

    expect(getSpy).toHaveBeenNthCalledWith(1, '/api/v1/user/me/profile')
    expect(getSpy).toHaveBeenNthCalledWith(2, '/api/v1/user/me/privacy')
    expect(result.needApproval).toBe(true)
    expect(result.nickname).toBe('Nexus User')
  })

  it('searchContent still calls the backend when mock flag is present', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              id: 'post-1',
              authorId: '7',
              authorNickname: 'Author',
              title: 'Search Title',
              description: 'Search Description',
              likeCount: 9,
              tags: ['tag-a']
            }
          ],
          nextAfter: 'next',
          hasMore: true
        }
      }
    } as never)

    const result = await searchContent('nexus')

    expect(getSpy).toHaveBeenCalledWith('/api/v1/search', {
      params: {
        q: 'nexus',
        size: 12
      }
    })
    expect(result.items[0]?.title).toBe('Search Title')
  })

  it('notification apis still call the backend when mock flag is present', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          notifications: [
            {
              notificationId: 1,
              title: 'Actor',
              content: 'Action',
              createTime: 1710000000000,
              bizType: 'COMMENT',
              targetType: 'POST',
              targetId: 10,
              postId: 10,
              unreadCount: 1
            }
          ],
          nextCursor: 'cursor-1'
        }
      }
    } as never)
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          success: true
        }
      }
    } as never)

    const notifications = await fetchNotifications()
    const result = await markAllNotificationsRead()

    expect(getSpy).toHaveBeenCalledWith('/api/v1/notification/list', {
      params: {
        cursor: undefined
      }
    })
    expect(postSpy).toHaveBeenCalledWith('/api/v1/notification/read/all')
    expect(notifications.notifications).toHaveLength(1)
    expect(result.success).toBe(true)
  })
})
