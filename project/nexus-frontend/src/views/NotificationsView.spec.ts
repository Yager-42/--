import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { expect, test, vi } from 'vitest'
import { useUiStore } from '@/stores/ui'

const notificationApiMocks = vi.hoisted(() => ({
  fetchNotifications: vi.fn().mockResolvedValue({
    notifications: [{ id: '1', actorName: 'Alice', actionText: 'liked your post', unread: true, timeLabel: '刚刚' }],
    nextCursor: ''
  }),
  markAllNotificationsRead: vi.fn().mockResolvedValue({
    success: true
  }),
  markNotificationRead: vi.fn().mockResolvedValue({
    success: true
  })
}))

vi.mock('@/services/api/notificationApi', () => ({
  fetchNotifications: notificationApiMocks.fetchNotifications,
  markAllNotificationsRead: notificationApiMocks.markAllNotificationsRead,
  markNotificationRead: notificationApiMocks.markNotificationRead
}))

import NotificationsView from '@/views/NotificationsView.vue'

test('renders unread notification rows distinctly', () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()]
    },
    props: {
      notifications: [
        { id: '1', actorName: 'Alice', actionText: 'liked your post', unread: true, timeLabel: '刚刚' }
      ]
    }
  })

  expect(wrapper.text()).toContain('Alice')
  expect(wrapper.find('[data-test=notification-unread]').exists()).toBe(true)
})

test('loads notifications from api when props are absent', async () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()]
    }
  })

  await flushPromises()

  const uiStore = useUiStore()

  expect(wrapper.text()).toContain('Alice')
  expect(wrapper.text()).toContain('liked your post')
  expect(uiStore.unreadNotificationCount).toBe(1)
})

test('marks a single notification as read', async () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()]
    }
  })

  await flushPromises()
  await wrapper.get('[data-test=mark-notification-read]').trigger('click')
  await flushPromises()

  const uiStore = useUiStore()

  expect(notificationApiMocks.markNotificationRead).toHaveBeenCalledWith('1')
  expect(uiStore.unreadNotificationCount).toBe(0)
})

test('marks an unread notification as read when opening its card', async () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      notifications: [
        {
          id: '3',
          actorName: 'Bob',
          actionText: '在评论中提到了你',
          unread: true,
          timeLabel: '刚刚',
          to: {
            name: 'post-detail',
            params: { id: '101' },
            query: {
              focus: 'comments',
              rootCommentId: '12',
              commentId: '18'
            }
          }
        }
      ]
    }
  })

  await wrapper.getComponent(RouterLinkStub).trigger('click')
  await flushPromises()

  expect(notificationApiMocks.markNotificationRead).toHaveBeenCalledWith('3')
})

test('renders comment mention notifications with explicit wording', () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()]
    },
    props: {
      notifications: [
        {
          id: '2',
          actorName: 'Bob',
          actionText: '在评论中提到了你',
          unread: true,
          timeLabel: '刚刚'
        }
      ]
    }
  })

  expect(wrapper.text()).toContain('Bob')
  expect(wrapper.text()).toContain('在评论中提到了你')
})

test('renders notification cards as deep links to post detail comment anchors', () => {
  setActivePinia(createPinia())
  const wrapper = mount(NotificationsView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      notifications: [
        {
          id: '3',
          actorName: 'Bob',
          actionText: '在评论中提到了你',
          unread: true,
          timeLabel: '刚刚',
          to: {
            name: 'post-detail',
            params: { id: '101' },
            query: {
              focus: 'comments',
              rootCommentId: '12',
              commentId: '18'
            }
          }
        }
      ]
    }
  })

  const link = wrapper.getComponent(RouterLinkStub)
  expect(link.props('to')).toEqual({
    name: 'post-detail',
    params: { id: '101' },
    query: {
      focus: 'comments',
      rootCommentId: '12',
      commentId: '18'
    }
  })
})
