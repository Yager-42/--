import { beforeEach, describe, expect, it, vi } from 'vitest'
import { searchContent, searchUsers } from '@/services/api/searchApi'
import { http } from '@/services/http/client'

describe('searchApi', () => {
  beforeEach(() => {
    localStorage.clear()
    http.defaults.adapter = undefined
  })

  it('maps author ids from search content results', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              id: 'post-1',
              authorId: 'user-8',
              title: 'Search result',
              description: 'Search description',
              authorNickname: 'Nexus Editorial',
              likeCount: 18,
              tags: ['design']
            }
          ],
          nextAfter: '',
          hasMore: false
        }
      }
    } as never)

    const response = await searchContent('nexus')

    expect(response.items[0].authorId).toBe('user-8')
  })

  it('maps user search results from author ids instead of content ids', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          items: [
            {
              id: 'post-1',
              authorId: 'user-8',
              title: 'Search result',
              description: 'Search description',
              authorNickname: 'Nexus Editorial',
              likeCount: 18,
              tags: ['design']
            }
          ],
          nextAfter: '',
          hasMore: false
        }
      }
    } as never)

    const users = await searchUsers('nexus')

    expect(users[0].id).toBe('user-8')
    expect(users[0].nickname).toBe('Nexus Editorial')
  })
})
