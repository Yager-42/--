import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { expect, test, vi } from 'vitest'
import FeedCard from '@/components/feed/FeedCard.vue'
import { usePostInteractionsStore } from '@/stores/postInteractions'

const interactionApiMocks = vi.hoisted(() => ({
  reactToTarget: vi.fn().mockResolvedValue({
    currentCount: 9,
    success: true
  })
}))

vi.mock('@/services/api/interactionApi', () => ({
  reactToTarget: interactionApiMocks.reactToTarget
}))

function resetStores() {
  setActivePinia(createPinia())
  usePostInteractionsStore().clearInteractions()
}

test('navigates to the post detail page from the feed card', () => {
  resetStores()

  const wrapper = mount(FeedCard, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      item: {
        id: '42',
        authorName: 'Nexus User',
        summary: 'Open the detail page',
        likeCountLabel: '8'
      }
    }
  })

  const link = wrapper.getComponent(RouterLinkStub)
  expect(link.props('to')).toBe('/post/42')
  expect(wrapper.text()).toContain('8')
  expect(wrapper.text()).toContain('赞')
})

test('updates the visible like count after liking the card', async () => {
  resetStores()

  const wrapper = mount(FeedCard, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      item: {
        id: '42',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Open the detail page',
        likeCountLabel: '8',
        liked: false
      }
    }
  })

  await wrapper.get('[data-test="feed-card-like-42"]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.reactToTarget).toHaveBeenCalledWith(
    expect.objectContaining({
      targetId: 42,
      action: 'ADD'
    })
  )
  expect(wrapper.text()).toContain('9')
  expect(wrapper.get('[data-test="feed-card-like-42"]').text()).toContain('已赞')
})

test('hydrates from shared post interaction state when the same post was liked elsewhere', () => {
  resetStores()
  const postInteractionsStore = usePostInteractionsStore()
  postInteractionsStore.updateInteraction('42', {
    liked: true,
    likeCountLabel: '19'
  })

  const wrapper = mount(FeedCard, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      item: {
        id: '42',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Open the detail page',
        likeCountLabel: '8',
        liked: false
      }
    }
  })

  expect(wrapper.get('[data-test="feed-card-like-42"]').text()).toContain('已赞')
  expect(wrapper.text()).toContain('19')
})
