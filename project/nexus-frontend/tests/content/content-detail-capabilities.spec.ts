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

const { fetchContentDetail, deleteContent } = vi.hoisted(() => ({
  fetchContentDetail: vi.fn(),
  deleteContent: vi.fn()
}))

const {
  fetchComments,
  fetchHotComments,
  fetchReactionState,
  deleteComment,
  pinComment,
  postComment,
  postReaction,
  fetchCommentReplies
} = vi.hoisted(() => ({
  fetchComments: vi.fn(),
  fetchHotComments: vi.fn(),
  fetchReactionState: vi.fn(),
  deleteComment: vi.fn(),
  pinComment: vi.fn(),
  postComment: vi.fn(),
  postReaction: vi.fn(),
  fetchCommentReplies: vi.fn()
}))

vi.mock('@/api/content', async () => {
  const actual = await vi.importActual<typeof import('@/api/content')>('@/api/content')
  return {
    ...actual,
    fetchContentDetail,
    deleteContent
  }
})

vi.mock('@/api/interact', async () => {
  const actual = await vi.importActual<typeof import('@/api/interact')>('@/api/interact')
  return {
    ...actual,
    fetchComments,
    fetchHotComments,
    fetchReactionState,
    deleteComment,
    pinComment,
    postComment,
    postReaction,
    fetchCommentReplies
  }
})

const buildDetail = (overrides: Partial<ContentDetailViewModel> = {}): ContentDetailViewModel => ({
  postId: 'post-quiet-light',
  authorId: '1',
  authorName: 'Nadia Rose',
  authorAvatar: 'https://example.com/author.jpg',
  title: 'The Architecture of Quiet Light',
  content: 'Paragraph one.\n\nParagraph two.\n\nParagraph three.',
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

const createTestRouter = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: defineComponent({ template: '<div>Home</div>' }) },
      { path: '/content/:postId', component: ContentDetail },
      { path: '/user/:userId', component: defineComponent({ template: '<div>User</div>' }) }
    ]
  })

  await router.push('/content/post-quiet-light')
  await router.isReady()
  return router
}

const mountDetail = async (authUserId = '1') => {
  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setToken('session-token', authUserId, 'refresh-token')

  const router = await createTestRouter()
  const wrapper = mount(ContentDetail, {
    attachTo: document.body,
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
          template:
            '<div><textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><button data-comment-submit @click="$emit(\'submit\')">Submit</button></div>'
        },
        ZenButton: {
          props: ['disabled', 'type'],
          emits: ['click'],
          template:
            '<button :disabled="disabled" :type="type || \'button\'" @click="$emit(\'click\')"><slot /></button>'
        },
        ZenIcon: { template: '<span />' }
      }
    }
  })

  await flushPromises()
  await flushPromises()

  return { wrapper, router, authStore }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''

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
  deleteComment.mockResolvedValue({ success: true })
  pinComment.mockResolvedValue({ success: true })
  deleteContent.mockResolvedValue({ success: true })
})

describe('content detail capability backfill', () => {
  test('renders a hot comment block ahead of the main thread', async () => {
    const { wrapper } = await mountDetail()

    expect(fetchHotComments).toHaveBeenCalledWith({
      postId: 'post-quiet-light',
      limit: 1,
      preloadReplyLimit: 2
    })
    expect(wrapper.text()).toContain('Hot comment')
    expect(wrapper.text()).toContain('Pinned by the author as the hot note.')
  })

  test('keeps the hot comment block when the comment list returns without a pinned item', async () => {
    let resolveComments: ((value: CommentListViewModel) => void) | null = null

    fetchComments.mockImplementation(
      () =>
        new Promise<CommentListViewModel>((resolve) => {
          resolveComments = resolve
        })
    )

    const detailPromise = mountDetail()

    await flushPromises()

    expect(resolveComments).not.toBeNull()
    resolveComments?.(buildCommentsResponse({ pinned: null }))

    const { wrapper } = await detailPromise
    await flushPromises()

    expect(wrapper.text()).toContain('Hot comment')
    expect(wrapper.text()).toContain('Pinned by the author as the hot note.')
  })

  test('shows delete and pin actions only when permitted', async () => {
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-comment-action="delete"]').exists()).toBe(true)
    expect(wrapper.find('[data-comment-action="pin"]').exists()).toBe(true)
  })

  test('shows a delete-post action for the author', async () => {
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-post-action="delete"]').exists()).toBe(true)
  })

  test('requires confirmation before destructive requests fire', async () => {
    const { wrapper } = await mountDetail()

    const deleteButtons = wrapper.findAll('[data-comment-action="delete"]')
    expect(deleteButtons).toHaveLength(2)

    await deleteButtons[1].trigger('click')
    expect(document.body.textContent).toContain('Are you sure')
    expect(deleteComment).not.toHaveBeenCalled()

    const confirmButton = document.body.querySelector('[data-confirm-accept]') as HTMLButtonElement | null
    expect(confirmButton).not.toBeNull()
    confirmButton?.click()
    await flushPromises()

    expect(deleteComment).toHaveBeenCalledWith('comment-2')
  })
})
