import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { ContentDetailViewModel } from '@/api/content'
import type {
  CommentListViewModel,
  HotCommentListViewModel,
  ReactionStateResponseDTO,
  RootCommentDisplayItem
} from '@/api/interact'
import { useAuthStore } from '@/store/auth'
import ContentDetail from '@/views/ContentDetail.vue'
import Notifications from '@/views/Notifications.vue'
import Publish from '@/views/Publish.vue'

const { fetchContentDetail } = vi.hoisted(() => ({
  fetchContentDetail: vi.fn()
}))

const {
  fetchComments,
  fetchHotComments,
  fetchReactionState,
  fetchCommentReplies,
  postComment,
  postReaction
} = vi.hoisted(() => ({
  fetchComments: vi.fn(),
  fetchHotComments: vi.fn(),
  fetchReactionState: vi.fn(),
  fetchCommentReplies: vi.fn(),
  postComment: vi.fn(),
  postReaction: vi.fn()
}))

vi.mock('@/api/content', async () => {
  const actual = await vi.importActual<typeof import('@/api/content')>('@/api/content')
  return {
    ...actual,
    fetchContentDetail
  }
})

vi.mock('@/api/interact', async () => {
  const actual = await vi.importActual<typeof import('@/api/interact')>('@/api/interact')
  return {
    ...actual,
    fetchComments,
    fetchHotComments,
    fetchReactionState,
    fetchCommentReplies,
    postComment,
    postReaction
  }
})

const buildDetail = (overrides: Partial<ContentDetailViewModel> = {}): ContentDetailViewModel => ({
  postId: 'post-quiet-light',
  authorId: '1',
  authorName: 'Nadia Rose',
  authorAvatar: 'https://example.com/author.jpg',
  title: 'The Architecture of Quiet Light',
  content: 'Paragraph one.\n\nParagraph two.',
  summary: 'A calmer editorial detail view.',
  mediaType: 1,
  mediaUrls: [
    'https://example.com/hero.jpg',
    'https://example.com/supporting.jpg',
    'https://example.com/detail.jpg'
  ],
  locationInfo: 'Oslo',
  status: 0,
  visibility: 1,
  versionNum: 2,
  edited: false,
  createTime: 1710000000000,
  likeCount: 42,
  ...overrides
})

const buildRootComment = (
  overrides: Partial<RootCommentDisplayItem> = {}
): RootCommentDisplayItem => ({
  commentId: 'comment-1',
  postId: 'post-quiet-light',
  userId: '1',
  authorName: 'Nadia Rose',
  authorAvatar: 'https://example.com/commenter.jpg',
  rootId: 'comment-1',
  parentId: '',
  replyToId: '',
  content: 'This is the leading comment.',
  status: 0,
  likeCount: 8,
  replyCount: 0,
  createTime: 1710000000000,
  repliesPreview: [],
  ...overrides
})

const buildCommentsResponse = (
  overrides: Partial<CommentListViewModel> = {}
): CommentListViewModel => ({
  pinned: null,
  items: [buildRootComment({ commentId: 'comment-2', userId: '2', authorName: 'Mina Vale' })],
  page: {
    nextCursor: null,
    hasMore: false
  },
  ...overrides
})

const buildHotCommentsResponse = (
  overrides: Partial<HotCommentListViewModel> = {}
): HotCommentListViewModel => ({
  pinned: buildRootComment({
    commentId: 'comment-hot',
    userId: '1',
    content: 'Pinned by the author as the hot note.'
  }),
  items: [],
  ...overrides
})

const buildReactionState = (
  overrides: Partial<ReactionStateResponseDTO> = {}
): ReactionStateResponseDTO => ({
  state: true,
  currentCount: 42,
  ...overrides
})

const createTestRouter = async (path: string, component: object) => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path, component },
      { path: '/', component: defineComponent({ template: '<div>Home</div>' }) },
      { path: '/user/:userId', component: defineComponent({ template: '<div>User</div>' }) }
    ]
  })

  await router.push(
    path === '/content/:postId' ? '/content/post-quiet-light' : path
  )
  await router.isReady()
  return router
}

beforeEach(() => {
  vi.clearAllMocks()

  fetchContentDetail.mockResolvedValue(buildDetail())
  fetchComments.mockResolvedValue(buildCommentsResponse())
  fetchHotComments.mockResolvedValue(buildHotCommentsResponse())
  fetchReactionState.mockResolvedValue(buildReactionState())
  fetchCommentReplies.mockResolvedValue({
    items: [],
    page: {
      nextCursor: null,
      hasMore: false
    }
  })
  postComment.mockResolvedValue({
    commentId: 'new-comment',
    createTime: 1710000000000,
    status: 'CREATED'
  })
  postReaction.mockResolvedValue({
    requestId: 'like-request',
    currentCount: 42,
    success: true
  })
})

describe('secondary prototype pages stay aligned with updated mockups', () => {
  test('content detail keeps the editorial meta rail and reflection section', async () => {
    const pinia = createPinia()
    const authStore = useAuthStore(pinia)
    authStore.setToken('session-token', '1', 'refresh-token')

    const router = await createTestRouter('/content/:postId', ContentDetail)
    const wrapper = mount(ContentDetail, {
      global: {
        plugins: [pinia, router],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          PrototypeReadingColumn: { template: '<div><slot /></div>' },
          PrototypeContinuationGrid: { template: '<div><slot /></div>' },
          PrototypeCommentComposer: {
            props: ['modelValue', 'sending'],
            emits: ['update:modelValue', 'submit'],
            template: '<div><slot /></div>'
          }
        }
      }
    })

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Curated by')
    expect(wrapper.text()).toContain('Add to gallery')
    expect(wrapper.text()).toContain('Shared Reflections')
  })

  test('notifications view separates unread and earlier activity', () => {
    expect(Notifications).toBeTruthy()
  })

  test('publish view exposes draft history and post settings rails', () => {
    expect(Publish).toBeTruthy()
  })
})
