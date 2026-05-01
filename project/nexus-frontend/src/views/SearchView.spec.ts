import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { suggestKeywords } from '@/services/api/searchApi'

const routeState = {
  query: {
    q: 'nexus',
    tab: 'users'
  }
}

const replace = vi.fn()

vi.mock('@/services/api/searchApi', () => ({
  searchContent: vi.fn().mockResolvedValue({
    items: [
      {
        id: 'result-1',
        authorId: 'user-8',
        title: '真实搜索结果',
        description: '来自后端搜索接口',
        authorName: 'Nexus Editorial',
        likeCountLabel: '18',
        tags: ['真实接口']
      }
    ],
    nextAfter: '',
    hasMore: false
  }),
  searchUsers: vi.fn().mockResolvedValue([
    {
      id: 'user-1',
      nickname: 'Nexus Editorial',
      bio: '来自后端用户搜索',
      isFollowing: false
    }
  ]),
  suggestKeywords: vi.fn().mockResolvedValue({
    items: ['nexus']
  })
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: routeState.query
  }),
  useRouter: () => ({
    replace
  })
}))

import SearchView from '@/views/SearchView.vue'

describe('SearchView', () => {
  beforeEach(() => {
    replace.mockReset()
    routeState.query = {
      q: 'nexus',
      tab: 'users'
    }
  })

  it('syncs the search input with the route query', () => {
    const wrapper = mount(SearchView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })

    expect((wrapper.get('[data-test=search-input]').element as HTMLInputElement).value).toBe('nexus')
    expect(wrapper.text()).toContain('用户')
    expect(wrapper.find('[data-test=search-tab-users]').attributes('aria-pressed')).toBe('true')
  })

  it('renders content results returned by search api as detail links', async () => {
    routeState.query = {
      q: 'nexus',
      tab: 'content'
    }

    const wrapper = mount(SearchView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('真实搜索结果')
    expect(wrapper.text()).toContain('来自后端搜索接口')

    const contentLink = wrapper
      .findAllComponents(RouterLinkStub)
      .find((item) => item.attributes('data-test') === 'search-content-link-result-1')

    expect(contentLink).toBeDefined()
    expect(contentLink!.props('to')).toBe('/post/result-1')
  })

  it('renders keyword suggestions while typing', async () => {
    routeState.query = {
      q: '',
      tab: 'content'
    }

    const wrapper = mount(SearchView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })

    await wrapper.get('[data-test=search-input]').setValue('nex')
    await flushPromises()

    expect(suggestKeywords).toHaveBeenCalledWith('nex')
    expect(wrapper.text()).toContain('nexus')
  })

  it('renders user results as links to profile pages', async () => {
    const wrapper = mount(SearchView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub
        }
      }
    })

    await flushPromises()

    const userLink = wrapper
      .findAllComponents(RouterLinkStub)
      .find((item) => item.attributes('data-test') === 'search-user-link-user-1')

    expect(userLink).toBeDefined()
    expect(userLink!.props('to')).toBe('/profile/user-1')
  })
})
