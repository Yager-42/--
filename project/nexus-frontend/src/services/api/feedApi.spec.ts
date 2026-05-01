import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http/client'
import { fetchNeighborsTimeline, fetchProfileTimeline, fetchTimeline } from '@/services/api/feedApi'

describe('feedApi', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('loads follow timeline with requested feed type', async () => {
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
              text: 'Body',
              summary: 'Summary',
              mediaType: 0,
              publishTime: 1710000000000,
              likeCount: 3,
              liked: false,
              followed: false,
              seen: false
            }
          ],
          nextCursor: 'cursor-1'
        }
      }
    } as never)

    const response = await fetchTimeline({ feedType: 'POPULAR', cursor: 'cursor-0' })

    expect(getSpy).toHaveBeenCalledWith('/api/v1/feed/timeline', {
      params: {
        cursor: 'cursor-0',
        limit: 20,
        feedType: 'POPULAR'
      }
    })
    expect(response.items[0].id).toBe('101')
    expect(response.nextCursor).toBe('cursor-1')
  })

  it('loads profile timeline', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [],
          nextCursor: ''
        }
      }
    } as never)

    await fetchProfileTimeline(7, 'cursor-2')

    expect(getSpy).toHaveBeenCalledWith('/api/v1/feed/profile/7', {
      params: {
        targetId: 7,
        cursor: 'cursor-2',
        limit: 20
      }
    })
  })

  it('loads neighbors timeline with the dedicated cursor format', async () => {
    const getSpy = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [],
          nextCursor: ''
        }
      }
    } as never)

    await fetchNeighborsTimeline(205)

    expect(getSpy).toHaveBeenCalledWith('/api/v1/feed/timeline', {
      params: {
        cursor: 'NEI:205:0',
        limit: 4,
        feedType: 'NEIGHBORS'
      }
    })
  })
})
