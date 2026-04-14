import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { NotificationDTO, NotificationListResponseDTO } from '@/api/notification'
import { useAuthStore } from '@/store/auth'

const { fetchNotifications, markAsRead, markAllAsRead } = vi.hoisted(() => ({
  fetchNotifications: vi.fn(),
  markAsRead: vi.fn(),
  markAllAsRead: vi.fn()
}))

vi.mock('@/api/notification', () => {
  return {
    fetchNotifications,
    markAsRead,
    markAllAsRead
  }
})

const { default: NotificationItem } = await import('@/components/NotificationItem.vue')
const { default: Notifications } = await import('@/views/Notifications.vue')

const buildNotification = (overrides: Partial<NotificationDTO> = {}): NotificationDTO => ({
  notificationId: 'notification-1',
  type: 'COMMENT',
  senderId: '2',
  senderName: 'Mina Vale',
  senderAvatar: 'https://example.com/avatar.jpg',
  content: 'Left a careful reply.',
  targetId: 'post-quiet-light',
  hasUnread: true,
  isRead: false,
  createTime: 1710000000000,
  ...overrides
})

const buildNotificationPage = (
  notifications: NotificationDTO[]
): NotificationListResponseDTO => ({
  notifications,
  page: {
    nextCursor: null,
    hasMore: false
  }
})

const createTestRouter = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div>Home</div>' }) },
      { path: '/notifications', component: Notifications },
      { path: '/content/:postId', component: defineComponent({ template: '<div>Content</div>' }) },
      { path: '/user/:userId', component: defineComponent({ template: '<div>User</div>' }) }
    ]
  })

  await router.push('/notifications')
  await router.isReady()
  return router
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  localStorage.clear()

  markAsRead.mockResolvedValue({ success: true })
  markAllAsRead.mockResolvedValue({ success: true })
})

describe('notification read flow', () => {
  test('marks a notification as read before routing to its target', async () => {
    const router = await createTestRouter()

    const wrapper = mount(NotificationItem, {
      props: {
        notification: buildNotification()
      },
      global: {
        plugins: [router]
      }
    })

    await wrapper.trigger('click')
    await flushPromises()

    expect(markAsRead).toHaveBeenCalledWith('notification-1')
    expect(router.currentRoute.value.fullPath).toBe('/content/post-quiet-light')
  })

  test('routes follow notifications to the profile surface instead of content detail', async () => {
    const router = await createTestRouter()

    const wrapper = mount(NotificationItem, {
      props: {
        notification: buildNotification({
          notificationId: 'notification-2',
          type: 'FOLLOW',
          targetId: '',
          senderId: '2'
        })
      },
      global: {
        plugins: [router]
      }
    })

    await wrapper.trigger('click')
    await flushPromises()

    expect(markAsRead).toHaveBeenCalledWith('notification-2')
    expect(router.currentRoute.value.fullPath).toBe('/user/2')
  })

  test('preserves mark-all-as-read after single-read wiring', async () => {
    fetchNotifications.mockResolvedValue(
      buildNotificationPage([
        buildNotification(),
        buildNotification({
          notificationId: 'notification-3',
          type: 'FOLLOW',
          senderId: '3',
          senderName: 'Nadia Rose',
          targetId: '',
          content: 'Followed you.'
        }),
        buildNotification({
          notificationId: 'notification-4',
          hasUnread: false,
          isRead: true
        })
      ])
    )

    const pinia = createPinia()
    const authStore = useAuthStore(pinia)
    authStore.setToken('session-token', '1', 'refresh-token')

    const router = await createTestRouter()
    const wrapper = mount(Notifications, {
      global: {
        plugins: [pinia, router],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          StatePanel: {
            props: ['title', 'body'],
            template: '<div><slot />{{ title }}{{ body }}</div>'
          },
          ZenButton: {
            props: ['disabled', 'type'],
            emits: ['click'],
            template:
              '<button :disabled="disabled" :type="type || \'button\'" @click="$emit(\'click\')"><slot /></button>'
          }
        }
      }
    })

    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(fetchNotifications).toHaveBeenCalledWith({
      userId: '1',
      cursor: undefined
    })
    expect(wrapper.text()).toContain('全部已读')
    expect(wrapper.text()).toContain('2 unread')

    const unreadItem = wrapper.findAllComponents(NotificationItem)[0]
    await unreadItem.trigger('click')
    await flushPromises()

    expect(markAsRead).toHaveBeenCalledWith('notification-1')
    expect(wrapper.text()).toContain('1 unread')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(markAllAsRead).toHaveBeenCalled()
  })
})
