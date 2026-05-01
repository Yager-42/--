import { flushPromises, mount } from '@vue/test-utils'
import { expect, test, vi } from 'vitest'
import { RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TimelineView from '@/views/TimelineView.vue'
import { usePostInteractionsStore } from '@/stores/postInteractions'

const feedApiMocks = vi.hoisted(() => ({
  fetchTimeline: vi.fn().mockImplementation((options?: { feedType?: string }) => {
    const feedType = options?.feedType ?? 'FOLLOW'

    if (feedType === 'POPULAR') {
      return Promise.resolve({
        items: [
          {
            id: '303',
            authorName: 'Popular User',
            summary: 'Loaded from popular API',
            likeCountLabel: '99'
          }
        ],
        nextCursor: 'POP:1'
      })
    }

    if (feedType === 'RECOMMEND') {
      return Promise.resolve({
        items: [
          {
            id: '404',
            authorName: 'Recommend User',
            summary: 'Loaded from recommend API',
            likeCountLabel: '66'
          }
        ],
        nextCursor: ''
      })
    }

    return Promise.resolve({
      items: [
        {
          id: '202',
          authorName: 'Remote User',
          summary: 'Loaded from API',
          likeCountLabel: '42'
        }
      ],
      nextCursor: ''
    })
  })
}))

vi.mock('@/services/api/feedApi', () => ({
  fetchTimeline: feedApiMocks.fetchTimeline
}))

function resetStores() {
  setActivePinia(createPinia())
  usePostInteractionsStore().clearInteractions()
}

test('renders feed cards from mapped timeline items', async () => {
  resetStores()
  const wrapper = mount(TimelineView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      initialItems: [
        {
          id: '101',
          authorName: 'Nexus User',
          summary: 'Hello editorial social',
          likeCountLabel: '12'
        }
      ]
    }
  })

  expect(wrapper.text()).toContain('Nexus User')
  expect(wrapper.text()).toContain('Hello editorial social')
})

test('loads timeline items from api when initial items are absent', async () => {
  resetStores()
  const wrapper = mount(TimelineView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  expect(wrapper.text()).toContain('Remote User')
  expect(wrapper.text()).toContain('Loaded from API')
})

test('switches between follow, popular, and recommend feeds', async () => {
  resetStores()
  const wrapper = mount(TimelineView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()
  expect(wrapper.text()).toContain('Loaded from API')

  await wrapper.get('[data-test=timeline-filter-POPULAR]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Loaded from popular API')

  await wrapper.get('[data-test=timeline-filter-RECOMMEND]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Loaded from recommend API')
})
