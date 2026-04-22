import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils'
import { expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { usePostInteractionsStore } from '@/stores/postInteractions'

const routeState = {
  params: {
    id: 'route-101'
  },
  query: {}
}

vi.mock('vue-router', () => ({
  useRoute: () => routeState
}))

const contentApiActionMocks = vi.hoisted(() => ({
  deleteComment: vi.fn().mockResolvedValue({
    success: true,
    id: 1,
    status: 'DELETED',
    message: 'ok'
  }),
  fetchPostDetail: vi.fn().mockResolvedValue({
    id: '101',
    authorId: '8',
    authorName: 'Remote Author',
    title: 'Detail',
    body: 'Expanded story body',
    summary: 'Expanded story',
    likeCountLabel: '12'
  })
}))

vi.mock('@/services/api/contentApi', () => ({
  fetchPostDetail: contentApiActionMocks.fetchPostDetail,
  fetchComments: vi.fn().mockResolvedValue({
    comments: [
      {
        id: 'c1',
        authorId: '8',
        authorName: 'Bob',
        body: 'First comment',
        replyCount: 1,
        repliesPreview: []
      }
    ]
  }),
  fetchHotComments: vi.fn().mockResolvedValue({
    pinned: {
      id: 'c0',
      authorId: '6',
      authorName: 'Pinned Editor',
      body: 'Pinned note',
      repliesPreview: []
    },
    items: [],
    nextCursor: ''
  }),
  fetchCommentReplies: vi.fn().mockResolvedValue({
    items: [
      {
        id: 'r1',
        authorId: '9',
        authorName: 'Reply Author',
        body: 'Expanded reply',
        createdAtLabel: '今天'
      }
    ],
    nextCursor: ''
  }),
  deleteComment: contentApiActionMocks.deleteComment
}))

const interactionApiMocks = vi.hoisted(() => ({
  reactToTarget: vi.fn().mockResolvedValue({
    requestId: 'req-1',
    currentCount: 13,
    success: true
  }),
  submitComment: vi.fn().mockResolvedValue({
    commentId: 99,
    createTime: 1710000000000,
    status: 'CREATED'
  }),
  pinComment: vi.fn().mockResolvedValue({
    success: true,
    id: 1,
    status: 'PINNED',
    message: 'ok'
  })
}))

vi.mock('@/services/api/interactionApi', () => ({
  reactToTarget: interactionApiMocks.reactToTarget,
  submitComment: interactionApiMocks.submitComment,
  pinComment: interactionApiMocks.pinComment
}))

const feedApiMocks = vi.hoisted(() => ({
  fetchNeighborsTimeline: vi.fn().mockResolvedValue({
    items: [
      {
        id: 's1',
        authorId: '12',
        authorName: 'Similar Author',
        summary: 'You may also like this story',
        likeCountLabel: '21'
      }
    ],
    nextCursor: 'NEI:101:1'
  })
}))

vi.mock('@/services/api/feedApi', () => ({
  fetchNeighborsTimeline: feedApiMocks.fetchNeighborsTimeline
}))

import PostDetailView from '@/views/PostDetailView.vue'

function loginAs(userId: number) {
  setActivePinia(createPinia())
  const authStore = useAuthStore()
  usePostInteractionsStore().clearInteractions()
  authStore.setCurrentUser({ userId, nickname: `User ${userId}` })
}

function resetAuth() {
  setActivePinia(createPinia())
  usePostInteractionsStore().clearInteractions()
  routeState.query = {}
}

test('renders the selected post with comments section', () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: 'p1',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  expect(wrapper.text()).toContain('Expanded story')
  expect(wrapper.text()).toContain('First comment')
})

test('keeps comment composer collapsed until user starts commenting', () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  expect(wrapper.find('[data-test=comment-input]').exists()).toBe(false)
  expect(wrapper.get('[data-test=open-comment-composer]').text()).toContain('写评论')
})

test('opens comment composer when user clicks write comment', async () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=open-comment-composer]').trigger('click')

  expect(wrapper.find('[data-test=comment-input]').exists()).toBe(true)
})

test('focuses the comment textarea when composer opens', async () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    attachTo: document.body,
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=open-comment-composer]').trigger('click')
  await flushPromises()

  expect(document.activeElement).toBe(wrapper.get('[data-test=comment-input]').element)
  wrapper.unmount()
})

test('loads post detail comments and similar posts from api when props are absent', async () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  expect(contentApiActionMocks.fetchPostDetail).toHaveBeenCalledWith('route-101')
  expect(feedApiMocks.fetchNeighborsTimeline).toHaveBeenCalledWith('101')
  expect(wrapper.text()).toContain('Remote Author')
  expect(wrapper.text()).toContain('Expanded story body')
  expect(wrapper.text()).toContain('First comment')
  expect(wrapper.text()).toContain('Similar Author')
  expect(wrapper.text()).toContain('类似内容推荐')
})

test('loads similar posts when the detail page is mounted with a post prop', async () => {
  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '205',
        authorId: '9',
        authorName: 'Preset Author',
        summary: 'Preset story',
        likeCountLabel: '5'
      },
      comments: []
    }
  })

  await flushPromises()

  expect(feedApiMocks.fetchNeighborsTimeline).toHaveBeenCalledWith('205')
  expect(wrapper.text()).toContain('Similar Author')
})

test('still shows the similar section when no neighbor posts are returned', async () => {
  feedApiMocks.fetchNeighborsTimeline.mockResolvedValueOnce({
    items: [],
    nextCursor: ''
  })

  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '205',
        authorId: '9',
        authorName: 'Preset Author',
        summary: 'Preset story',
        likeCountLabel: '5'
      },
      comments: []
    }
  })

  await flushPromises()

  expect(wrapper.text()).toContain('类似内容推荐')
  expect(wrapper.text()).toContain('暂时没有相似内容')
})

test('shows an error state for the similar section when neighbor api fails', async () => {
  feedApiMocks.fetchNeighborsTimeline.mockRejectedValueOnce(new Error('neighbors failed'))

  resetAuth()
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '205',
        authorId: '9',
        authorName: 'Preset Author',
        summary: 'Preset story',
        likeCountLabel: '5'
      },
      comments: []
    }
  })

  await flushPromises()

  expect(wrapper.text()).toContain('类似内容推荐')
  expect(wrapper.text()).toContain('neighbors failed')
  expect(wrapper.find('[data-test=retry-similar-posts]').exists()).toBe(true)
})

test('likes a post and submits a root comment after opening composer', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: 'p1',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12',
        liked: false
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=like-button]').trigger('click')
  await wrapper.get('[data-test=open-comment-composer]').trigger('click')
  await wrapper.get('[data-test=comment-input]').setValue('New comment')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.reactToTarget).toHaveBeenCalled()
  expect(interactionApiMocks.submitComment).toHaveBeenCalledWith({
    postId: 1,
    content: 'New comment'
  })
  expect(wrapper.text()).toContain('New comment')
})

test('hydrates liked state from the shared post interaction store', async () => {
  resetAuth()
  const postInteractionsStore = usePostInteractionsStore()
  postInteractionsStore.updateInteraction('p1', {
    liked: true,
    likeCountLabel: '13'
  })

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: 'p1',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12',
        liked: false
      },
      comments: []
    }
  })

  expect(wrapper.get('[data-test=like-button]').text()).toContain('已赞')
  expect(wrapper.get('[data-test=like-button]').text()).toContain('13')
})

test('persists detail-page like changes into the shared post interaction store', async () => {
  loginAs(7)

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: 'p1',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12',
        liked: false
      },
      comments: []
    }
  })

  await wrapper.get('[data-test=like-button]').trigger('click')
  await flushPromises()

  expect(usePostInteractionsStore().getInteraction('p1')).toEqual({
    liked: true,
    likeCountLabel: '13'
  })
})

test('replies to a comment with parent id payload and opens composer automatically', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=reply-comment-c1]').trigger('click')
  expect(wrapper.find('[data-test=comment-input]').exists()).toBe(true)

  await wrapper.get('[data-test=comment-input]').setValue('Reply body')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.submitComment).toHaveBeenLastCalledWith({
    postId: 101,
    parentId: 1,
    content: 'Reply body'
  })
  expect(wrapper.text()).toContain('Reply body')
})

test('shows a newly created reply at the top of the root comment thread', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        {
          id: 'c1',
          authorId: '8',
          authorName: 'Bob',
          body: 'First comment',
          replyCount: 2,
          repliesPreview: [
            { id: 'r1', authorId: '9', authorName: 'Reply A', body: 'Earlier reply' },
            { id: 'r2', authorId: '10', authorName: 'Reply B', body: 'Older reply' }
          ]
        }
      ]
    }
  })

  await wrapper.get('[data-test=reply-comment-c1]').trigger('click')
  await wrapper.get('[data-test=comment-input]').setValue('Newest reply')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  const replies = wrapper.findAll('[data-test^="reply-reply-"]')
  expect(replies[0]?.attributes('data-test')).toBe('reply-reply-99')
  expect(wrapper.text()).toContain('Newest reply')
})

test('replies to a reply using the root comment id instead of the reply id', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        {
          id: 'c1',
          authorId: '8',
          authorName: 'Bob',
          body: 'First comment',
          replyCount: 1,
          replies: [{ id: 'r9', authorId: '9', authorName: 'Reply Author', body: 'Expanded reply', canDelete: false }],
          repliesPreview: []
        }
      ]
    }
  })

  await wrapper.get('[data-test=reply-reply-r9]').trigger('click')
  expect((wrapper.get('[data-test=comment-input]').element as HTMLTextAreaElement).value).toBe('@Reply Author ')

  await wrapper.get('[data-test=comment-input]').setValue('@Reply Author I agree')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.submitComment).toHaveBeenLastCalledWith({
    postId: 101,
    parentId: 1,
    content: '@Reply Author I agree'
  })
  expect(wrapper.text()).toContain('@Reply Author I agree')
})

test('replies to a newly created root comment with the server comment id', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=open-comment-composer]').trigger('click')
  await wrapper.get('[data-test=comment-input]').setValue('Fresh root comment')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect(wrapper.find('[data-test=reply-comment-99]').exists()).toBe(true)

  await wrapper.get('[data-test=reply-comment-99]').trigger('click')
  await wrapper.get('[data-test=comment-input]').setValue('Reply to fresh root')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.submitComment).toHaveBeenLastCalledWith({
    postId: 101,
    parentId: 99,
    content: 'Reply to fresh root'
  })
  expect(wrapper.text()).toContain('Reply to fresh root')
})

test('keeps reply composer content and shows an error when reply submit fails', async () => {
  loginAs(7)
  interactionApiMocks.submitComment.mockRejectedValueOnce(new Error('非法参数'))

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [{ id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [] }]
    }
  })

  await wrapper.get('[data-test=reply-comment-c1]').trigger('click')
  await wrapper.get('[data-test=comment-input]').setValue('Reply body')
  await wrapper.get('[data-test=comment-submit]').trigger('click')
  await flushPromises()

  expect((wrapper.get('[data-test=comment-input]').element as HTMLTextAreaElement).value).toBe('Reply body')
  expect(wrapper.text()).toContain('非法参数')
})

test('loads hot comments and expands replies', async () => {
  resetAuth()
  routeState.query = {}
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()
  expect(wrapper.text()).toContain('Pinned Editor')

  await wrapper.get('[data-test=view-replies-c1]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Expanded reply')
})

test('expands targeted comment thread from route query and highlights the target comment', async () => {
  resetAuth()
  routeState.query = {
    focus: 'comments',
    rootCommentId: 'c1',
    commentId: 'r1'
  }

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  expect(wrapper.text()).toContain('Expanded reply')
  expect(wrapper.get('[data-comment-id="r1"]').attributes('data-comment-focused')).toBe('true')
})

test('only shows pin action for the post author', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '9',
        authorName: 'Post Owner',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        { id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [], canPin: true }
      ]
    }
  })

  expect(wrapper.find('[data-test=pin-comment-c1]').exists()).toBe(false)
})

test('pins a comment to the top of the conversation when current user is the post author', async () => {
  resetAuth()
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        { id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [], canPin: true },
        { id: 'c2', authorId: '9', authorName: 'Carol', body: 'Second comment', repliesPreview: [], canPin: true }
      ]
    }
  })

  expect(wrapper.find('[data-test=pin-comment-c1]').exists()).toBe(true)

  await wrapper.get('[data-test=pin-comment-c1]').trigger('click')
  await flushPromises()

  expect(interactionApiMocks.pinComment).toHaveBeenCalledWith({
    commentId: 1,
    postId: 101
  })
  expect(wrapper.text()).toContain('置顶')
})

test('deletes a comment after confirmation', async () => {
  loginAs(8)
  vi.stubGlobal('confirm', vi.fn(() => true))

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        { id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [], canDelete: true },
        { id: 'c2', authorId: '9', authorName: 'Carol', body: 'Second comment', repliesPreview: [], canDelete: true }
      ]
    }
  })

  await wrapper.get('[data-test=delete-comment-c1]').trigger('click')
  await flushPromises()

  expect(contentApiActionMocks.deleteComment).toHaveBeenCalledWith(1)
  expect(wrapper.text()).not.toContain('First comment')
  vi.unstubAllGlobals()
})

test('only shows delete actions for comments created by the current user', async () => {
  loginAs(7)
  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '9',
        authorName: 'Post Owner',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        { id: 'c1', authorId: '8', authorName: 'Bob', body: 'First comment', repliesPreview: [], canDelete: true },
        {
          id: 'c2',
          authorId: '7',
          authorName: 'Current User',
          body: 'Second comment',
          repliesPreview: [{ id: 'r1', authorId: '7', authorName: 'Current User', body: 'My reply', canDelete: true }]
        }
      ]
    }
  })

  expect(wrapper.find('[data-test=delete-comment-c1]').exists()).toBe(false)
  expect(wrapper.find('[data-test=delete-comment-c2]').exists()).toBe(true)
  expect(wrapper.find('[data-test=delete-reply-r1]').exists()).toBe(true)
})

test('deletes a reply after confirmation', async () => {
  loginAs(9)
  vi.stubGlobal('confirm', vi.fn(() => true))

  const wrapper = mount(PostDetailView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub
      }
    },
    props: {
      post: {
        id: '101',
        authorId: '7',
        authorName: 'Nexus User',
        summary: 'Expanded story',
        likeCountLabel: '12'
      },
      comments: [
        {
          id: 'c1',
          authorId: '8',
          authorName: 'Bob',
          body: 'First comment',
          replyCount: 1,
          replies: [{ id: 'r1', authorId: '9', authorName: 'Reply Author', body: 'Expanded reply', canDelete: true }],
          repliesPreview: []
        }
      ]
    }
  })

  await wrapper.get('[data-test=delete-reply-r1]').trigger('click')
  await flushPromises()

  expect(contentApiActionMocks.deleteComment).toHaveBeenCalledWith(1)
  expect(wrapper.text()).not.toContain('Expanded reply')
  vi.unstubAllGlobals()
})
