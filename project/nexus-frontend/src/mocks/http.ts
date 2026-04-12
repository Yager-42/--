type MockMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'

export interface MockRequestOptions {
  params?: Record<string, unknown>
  data?: unknown
  headers?: Record<string, string | undefined>
}

interface UserRecord {
  userId: string
  phone: string
  username: string
  nickname: string
  avatarUrl: string
  status: 'ACTIVE' | 'LIMITED' | 'BANNED'
}

interface PostRecord {
  postId: string
  authorId: string
  title: string
  summary: string
  text: string
  mediaUrls: string[]
  locationInfo: string
  publishTime: number
  versionNum: number
  likeCount: number
  likedBy: Set<string>
  tags: string[]
}

interface CommentRecord {
  commentId: string
  postId: string
  userId: string
  rootId: string
  parentId: string
  replyToId: string
  content: string
  status: number
  likeCount: number
  createTime: number
}

interface RootCommentRecord {
  root: CommentRecord
  replies: CommentRecord[]
}

interface NotificationRecord {
  notificationId: string
  title: string
  content: string
  bizType: string
  targetId: string
  postId: string
  lastActorUserId: string
  unreadCount: number
  createTime: number
}

interface DraftRecord {
  draftId: string
  userId: string
  title: string
  contentText: string
  mediaIds: string[]
}

interface RiskRecord {
  status: 'NORMAL' | 'LIMITED' | 'BANNED'
  capabilities: string[]
}

interface AuthMeRecord {
  userId: string
  phone: string
  status: string
  nickname: string
  avatarUrl: string
}

interface ContentVersionRecord {
  versionId: string
  title: string
  content: string
  time: number
}

interface PublishAttemptRecord {
  attemptId: string
  postId: string
  userId: string
  idempotentToken: string
  transcodeJobId: string
  attemptStatus: number
  riskStatus: number
  transcodeStatus: number
  publishedVersionNum: number
  errorCode: string
  errorMessage: string
  createTime: number
  updateTime: number
}

interface ScheduleTaskRecord {
  taskId: string
  postId: string
  userId: string
  scheduleTime: number
  status: number
  retryCount: number
  isCanceled: number
  lastError: string
  alarmSent: number
  contentData: string
}

type FeedType = 'FOLLOWING' | 'RECOMMENDED' | 'TRENDING'

const now = Date.now()

const users = new Map<string, UserRecord>([
  [
    '1',
    {
      userId: '1',
      phone: '13800000000',
      username: 'curator.one',
      nickname: 'Iris Calder',
      avatarUrl: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=320&q=80',
      status: 'ACTIVE'
    }
  ],
  [
    '2',
    {
      userId: '2',
      phone: '13800000002',
      username: 'studio.light',
      nickname: 'Milo Ardent',
      avatarUrl: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=320&q=80',
      status: 'ACTIVE'
    }
  ],
  [
    '3',
    {
      userId: '3',
      phone: '13800000003',
      username: 'quiet.notes',
      nickname: 'Lena Voss',
      avatarUrl: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=320&q=80',
      status: 'ACTIVE'
    }
  ],
  [
    '4',
    {
      userId: '4',
      phone: '13800000004',
      username: 'plain.spaces',
      nickname: 'Oren Hale',
      avatarUrl: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=320&q=80',
      status: 'LIMITED'
    }
  ]
])

const postRecords = new Map<string, PostRecord>([
  [
    'post-quiet-light',
    {
      postId: 'post-quiet-light',
      authorId: '2',
      title: 'The Architecture of Quiet Light',
      summary: 'How restrained contrast builds emotional focus in modern interiors.',
      text:
        'Soft light gives structure to silence.\n\nIn minimalist spaces, contrast should guide the eye, not dominate it.\n\nWhen materials are neutral and transitions are calm, every object gains narrative weight.',
      mediaUrls: [
        'https://images.unsplash.com/photo-1505691938895-1758d7feb511?w=1200&q=80',
        'https://images.unsplash.com/photo-1493666438817-866a91353ca9?w=1200&q=80',
        'https://images.unsplash.com/photo-1493666438817-866a91353ca9?w=1000&q=80'
      ],
      locationInfo: 'Oslo',
      publishTime: now - 1000 * 60 * 38,
      versionNum: 1,
      likeCount: 126,
      likedBy: new Set(['1']),
      tags: ['interior', 'minimal', 'light']
    }
  ],
  [
    'post-slow-editorial',
    {
      postId: 'post-slow-editorial',
      authorId: '3',
      title: 'Editing for Slower Reading',
      summary: 'A practical sequence for building calmer long-form pages.',
      text:
        'Readers keep attention when hierarchy is obvious.\n\nIntroduce one dominant heading, then keep the rhythm stable.\n\nWhite space is pacing, not decoration.',
      mediaUrls: [
        'https://images.unsplash.com/photo-1493666438817-866a91353ca9?w=1200&q=80',
        'https://images.unsplash.com/photo-1519710164239-da123dc03ef4?w=1200&q=80'
      ],
      locationInfo: 'Copenhagen',
      publishTime: now - 1000 * 60 * 95,
      versionNum: 1,
      likeCount: 78,
      likedBy: new Set(),
      tags: ['editorial', 'ux', 'writing']
    }
  ],
  [
    'post-ambient-archive',
    {
      postId: 'post-ambient-archive',
      authorId: '4',
      title: 'Ambient Archive Systems',
      summary: 'Designing archive discovery without noisy recommendation surfaces.',
      text:
        'Discovery should feel deliberate.\n\nNavigation can stay deep and still feel simple when labels are clear and spacing is consistent.',
      mediaUrls: [
        'https://images.unsplash.com/photo-1519710164239-da123dc03ef4?w=1200&q=80'
      ],
      locationInfo: 'Tokyo',
      publishTime: now - 1000 * 60 * 60 * 7,
      versionNum: 1,
      likeCount: 44,
      likedBy: new Set(['3']),
      tags: ['archive', 'information', 'systems']
    }
  ]
])

const timelineOrder: string[] = ['post-quiet-light', 'post-slow-editorial', 'post-ambient-archive']

const relationEdges = new Set<string>(['1->2', '1->3', '2->1', '3->2'])
const relationTimes = new Map<string, number>([
  ['1->2', now - 1000 * 60 * 60 * 24 * 18],
  ['1->3', now - 1000 * 60 * 60 * 24 * 4],
  ['2->1', now - 1000 * 60 * 60 * 24 * 12],
  ['3->2', now - 1000 * 60 * 60 * 24 * 2]
])

const commentsByPost = new Map<string, RootCommentRecord[]>([
  [
    'post-quiet-light',
    [
      {
        root: {
          commentId: 'c-root-1',
          postId: 'post-quiet-light',
          userId: '3',
          rootId: 'c-root-1',
          parentId: '',
          replyToId: '',
          content: 'The pacing is excellent. The second paragraph lands very well.',
          status: 0,
          likeCount: 6,
          createTime: now - 1000 * 60 * 32
        },
        replies: [
          {
            commentId: 'c-reply-1',
            postId: 'post-quiet-light',
            userId: '1',
            rootId: 'c-root-1',
            parentId: 'c-root-1',
            replyToId: '3',
            content: 'Agreed. The transition between paragraphs is very smooth.',
            status: 0,
            likeCount: 1,
            createTime: now - 1000 * 60 * 24
          },
          {
            commentId: 'c-reply-2',
            postId: 'post-quiet-light',
            userId: '2',
            rootId: 'c-root-1',
            parentId: 'c-root-1',
            replyToId: '1',
            content: 'Thanks, this is exactly the pacing target.',
            status: 0,
            likeCount: 0,
            createTime: now - 1000 * 60 * 18
          }
        ]
      },
      {
        root: {
          commentId: 'c-root-2',
          postId: 'post-quiet-light',
          userId: '4',
          rootId: 'c-root-2',
          parentId: '',
          replyToId: '',
          content: 'Would love to see a follow-up on typography scale.',
          status: 0,
          likeCount: 2,
          createTime: now - 1000 * 60 * 14
        },
        replies: []
      }
    ]
  ]
])

const notifications: NotificationRecord[] = [
  {
    notificationId: 'n-1001',
    title: 'Milo Ardent',
    content: 'liked your post',
    bizType: 'LIKE',
    targetId: 'post-quiet-light',
    postId: 'post-quiet-light',
    lastActorUserId: '2',
    unreadCount: 1,
    createTime: now - 1000 * 60 * 25
  },
  {
    notificationId: 'n-1002',
    title: 'Lena Voss',
    content: 'commented on your story',
    bizType: 'COMMENT',
    targetId: 'post-quiet-light',
    postId: 'post-quiet-light',
    lastActorUserId: '3',
    unreadCount: 1,
    createTime: now - 1000 * 60 * 44
  },
  {
    notificationId: 'n-1003',
    title: 'Oren Hale',
    content: 'started following you',
    bizType: 'FOLLOW',
    targetId: '1',
    postId: '',
    lastActorUserId: '4',
    unreadCount: 0,
    createTime: now - 1000 * 60 * 75
  }
]

const risks = new Map<string, RiskRecord>([
  ['1', { status: 'NORMAL', capabilities: ['POST', 'COMMENT', 'DM'] }],
  ['2', { status: 'NORMAL', capabilities: ['POST', 'COMMENT'] }],
  ['3', { status: 'LIMITED', capabilities: ['COMMENT'] }],
  ['4', { status: 'BANNED', capabilities: [] }]
])

const drafts = new Map<string, DraftRecord>()
const blockedEdges = new Set<string>()
const privacySettings = new Map<string, boolean>([['1', false], ['2', false], ['3', true], ['4', true]])
const contentHistories = new Map<string, ContentVersionRecord[]>()
const publishAttempts = new Map<string, PublishAttemptRecord>()
const scheduleTasks = new Map<string, ScheduleTaskRecord>()
const passwordByUserId = new Map<string, string>([
  ['1', 'old-pass'],
  ['2', 'old-pass'],
  ['3', 'old-pass'],
  ['4', 'old-pass']
])
let draftSeq = 1
let commentSeq = 100
let appealSeq = 10
let uploadSeq = 10
let publishSeq = 10
let scheduleSeq = 1
let historySeq = 1
let currentUserId: string | null = '1'

export const UI_MOCK_DEFAULT_SESSION = {
  userId: '1',
  token: 'mock-token-1',
  refreshToken: 'mock-refresh-1'
}

const toPath = (url: string): string => {
  const pathWithQuery = /^https?:\/\//i.test(url) ? new URL(url).pathname + new URL(url).search : url
  const [pathOnly] = pathWithQuery.split('?')
  const path = pathOnly.startsWith('/api/v1') ? pathOnly.slice('/api/v1'.length) : pathOnly
  return path.startsWith('/') ? path : `/${path}`
}

const toQueryParams = (url: string): Record<string, unknown> => {
  const queryText = url.includes('?') ? url.slice(url.indexOf('?') + 1) : ''
  if (!queryText) {
    return {}
  }

  const params = new URLSearchParams(queryText)
  const result: Record<string, unknown> = {}
  params.forEach((value, key) => {
    result[key] = value
  })
  return result
}

const toRecord = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {}
  }
  return value as Record<string, unknown>
}

const toString = (value: unknown, fallback = ''): string => {
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return fallback
}

const toNumber = (value: unknown, fallback: number): number => {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

const requireCurrentUserId = (): string => {
  if (!currentUserId) {
    throw new Error('mock auth required')
  }
  return currentUserId
}

const pickUser = (userId: string): UserRecord => {
  return users.get(userId) ?? users.get('1')!
}

const edgeKey = (sourceId: string, targetId: string): string => `${sourceId}->${targetId}`

const isFollowing = (sourceId: string, targetId: string): boolean => {
  return relationEdges.has(edgeKey(sourceId, targetId))
}

const followCountOf = (userId: string): number => {
  let count = 0
  relationEdges.forEach((edge) => {
    if (edge.startsWith(`${userId}->`)) {
      count += 1
    }
  })
  return count
}

const followerCountOf = (userId: string): number => {
  let count = 0
  relationEdges.forEach((edge) => {
    const [, targetId] = edge.split('->')
    if (targetId === userId) {
      count += 1
    }
  })
  return count
}

const toMediaInfo = (urls: string[]): string => JSON.stringify(urls)

const mapFeedItem = (post: PostRecord, source: FeedType = 'FOLLOWING') => {
  const author = pickUser(post.authorId)
  const viewerId = currentUserId
  return {
    postId: post.postId,
    authorId: post.authorId,
    authorNickname: author.nickname,
    authorAvatar: author.avatarUrl,
    text: post.text,
    summary: post.summary,
    mediaType: post.mediaUrls.length > 0 ? 1 : 0,
    mediaInfo: toMediaInfo(post.mediaUrls),
    publishTime: post.publishTime,
    source,
    likeCount: post.likeCount,
    liked: viewerId ? post.likedBy.has(viewerId) : false,
    followed: viewerId ? isFollowing(viewerId, post.authorId) : false,
    seen: true
  }
}

const normalizeFeedType = (value: unknown): FeedType => {
  const nextValue = toString(value, 'FOLLOWING').toUpperCase()
  if (nextValue === 'TRENDING') {
    return 'TRENDING'
  }
  if (nextValue === 'RECOMMENDED') {
    return 'RECOMMENDED'
  }
  return 'FOLLOWING'
}

const getTimelineRecords = (feedType: FeedType): PostRecord[] => {
  const viewerId = currentUserId
  const posts = timelineOrder
    .map((id) => postRecords.get(id))
    .filter((post): post is PostRecord => Boolean(post))

  if (feedType === 'TRENDING') {
    return posts.sort((left, right) => {
      if (right.likeCount !== left.likeCount) {
        return right.likeCount - left.likeCount
      }
      return right.publishTime - left.publishTime
    })
  }

  if (feedType === 'RECOMMENDED') {
    return posts.sort((left, right) => {
      const leftFollowed = viewerId && isFollowing(viewerId, left.authorId) ? 1 : 0
      const rightFollowed = viewerId && isFollowing(viewerId, right.authorId) ? 1 : 0
      if (leftFollowed !== rightFollowed) {
        return leftFollowed - rightFollowed
      }
      if (right.publishTime !== left.publishTime) {
        return right.publishTime - left.publishTime
      }
      return right.likeCount - left.likeCount
    })
  }

  return posts.sort((left, right) => {
    const leftFollowed = viewerId && isFollowing(viewerId, left.authorId) ? 1 : 0
    const rightFollowed = viewerId && isFollowing(viewerId, right.authorId) ? 1 : 0
    if (leftFollowed !== rightFollowed) {
      return rightFollowed - leftFollowed
    }
    return right.publishTime - left.publishTime
  })
}

const mapContentDetail = (post: PostRecord) => {
  const author = pickUser(post.authorId)
  return {
    postId: post.postId,
    authorId: post.authorId,
    authorNickname: author.nickname,
    authorAvatarUrl: author.avatarUrl,
    title: post.title,
    content: post.text,
    summary: post.summary,
    summaryStatus: 1,
    mediaType: post.mediaUrls.length > 0 ? 1 : 0,
    mediaInfo: toMediaInfo(post.mediaUrls),
    locationInfo: post.locationInfo,
    status: 0,
    visibility: 1,
    versionNum: post.versionNum,
    edited: false,
    createTime: post.publishTime,
    likeCount: post.likeCount
  }
}

const mapCommentRow = (comment: CommentRecord) => {
  const author = pickUser(comment.userId)
  return {
    commentId: comment.commentId,
    postId: comment.postId,
    userId: comment.userId,
    nickname: author.nickname,
    avatarUrl: author.avatarUrl,
    rootId: comment.rootId,
    parentId: comment.parentId || null,
    replyToId: comment.replyToId || null,
    content: comment.content,
    status: comment.status,
    likeCount: comment.likeCount,
    replyCount: 0,
    createTime: comment.createTime
  }
}

const mapRootCommentRow = (item: RootCommentRecord, preloadReplyLimit: number) => ({
  root: {
    ...mapCommentRow(item.root),
    replyCount: item.replies.length
  },
  repliesPreview: item.replies.slice(0, preloadReplyLimit).map(mapCommentRow)
})

const findRootRecord = (rootId: string): RootCommentRecord | null => {
  for (const rows of commentsByPost.values()) {
    const hit = rows.find((item) => item.root.commentId === rootId)
    if (hit) {
      return hit
    }
  }
  return null
}

const parseMediaIds = (value: unknown): string[] => {
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
  }
  return []
}

const clone = <T>(value: T): T => {
  if (typeof structuredClone === 'function') {
    return structuredClone(value)
  }
  return JSON.parse(JSON.stringify(value)) as T
}

const createResponse = <T>(value: T): T => clone(value)

const createOperationResult = (
  id?: string,
  status = 'OK',
  message = ''
) => createResponse({ success: true, id, status, message })

const upsertHistoryVersion = (postId: string, title: string, content: string, time = Date.now()) => {
  const versions = contentHistories.get(postId) ?? []
  versions.unshift({
    versionId: `version-${historySeq++}`,
    title,
    content,
    time
  })
  contentHistories.set(postId, versions)
  return versions[0]
}

const buildScheduleContentData = (postId: string): string => {
  const post = postRecords.get(postId)
  return JSON.stringify({
    postId,
    title: post?.title ?? '',
    text: post?.text ?? '',
    mediaUrls: post?.mediaUrls ?? []
  })
}

const removeCommentById = (commentId: string): boolean => {
  for (const [postId, rows] of commentsByPost.entries()) {
    const rootIndex = rows.findIndex((item) => item.root.commentId === commentId)
    if (rootIndex >= 0) {
      rows.splice(rootIndex, 1)
      commentsByPost.set(postId, rows)
      return true
    }

    for (const row of rows) {
      const replyIndex = row.replies.findIndex((reply) => reply.commentId === commentId)
      if (replyIndex >= 0) {
        row.replies.splice(replyIndex, 1)
        commentsByPost.set(postId, rows)
        return true
      }
    }
  }
  return false
}

const authMePayload = (userId: string): AuthMeRecord => {
  const user = pickUser(userId)
  return {
    userId: user.userId,
    phone: user.phone,
    status: user.status,
    nickname: user.nickname,
    avatarUrl: user.avatarUrl
  }
}

const restoreMap = <K, V>(target: Map<K, V>, entries: Array<[K, V]>) => {
  target.clear()
  entries.forEach(([key, value]) => {
    target.set(key, value)
  })
}

const restoreSet = <T>(target: Set<T>, entries: T[]) => {
  target.clear()
  entries.forEach((entry) => {
    target.add(entry)
  })
}

const initialUsersSnapshot = clone(Array.from(users.entries()))
const initialPostsSnapshot = clone(Array.from(postRecords.entries()))
const initialTimelineSnapshot = clone(timelineOrder)
const initialRelationEdgesSnapshot = clone(Array.from(relationEdges))
const initialRelationTimesSnapshot = clone(Array.from(relationTimes.entries()))
const initialCommentsSnapshot = clone(Array.from(commentsByPost.entries()))
const initialNotificationsSnapshot = clone(notifications)
const initialRisksSnapshot = clone(Array.from(risks.entries()))
const initialPrivacySnapshot = clone(Array.from(privacySettings.entries()))
const initialPasswordsSnapshot = clone(Array.from(passwordByUserId.entries()))

export const resetUIMockState = (): void => {
  restoreMap(users, clone(initialUsersSnapshot))
  restoreMap(postRecords, clone(initialPostsSnapshot))
  timelineOrder.splice(0, timelineOrder.length, ...clone(initialTimelineSnapshot))
  restoreSet(relationEdges, clone(initialRelationEdgesSnapshot))
  restoreMap(relationTimes, clone(initialRelationTimesSnapshot))
  restoreMap(commentsByPost, clone(initialCommentsSnapshot))
  notifications.splice(0, notifications.length, ...clone(initialNotificationsSnapshot))
  restoreMap(risks, clone(initialRisksSnapshot))
  restoreMap(privacySettings, clone(initialPrivacySnapshot))
  restoreMap(passwordByUserId, clone(initialPasswordsSnapshot))
  drafts.clear()
  blockedEdges.clear()
  contentHistories.clear()
  publishAttempts.clear()
  scheduleTasks.clear()
  draftSeq = 1
  commentSeq = 100
  appealSeq = 10
  uploadSeq = 10
  publishSeq = 10
  scheduleSeq = 1
  historySeq = 1
  currentUserId = UI_MOCK_DEFAULT_SESSION.userId

  for (const post of postRecords.values()) {
    contentHistories.set(post.postId, [
      {
        versionId: `version-${historySeq++}`,
        title: post.title,
        content: post.text,
        time: post.publishTime
      }
    ])
  }
}

resetUIMockState()

const handleGet = (path: string, params: Record<string, unknown>) => {
  if (path === '/feed/timeline') {
    const cursor = toNumber(params.cursor, 0)
    const limit = Math.max(1, toNumber(params.limit, 10))
    const feedType = normalizeFeedType(params.feedType)
    const records = getTimelineRecords(feedType)
    const items = records
      .slice(cursor, cursor + limit)
      .filter((post): post is PostRecord => Boolean(post))
      .map((post) => mapFeedItem(post, feedType))
    const nextCursor = cursor + limit < records.length ? String(cursor + limit) : null
    return createResponse({ items, nextCursor })
  }

  if (path.startsWith('/feed/profile/')) {
    const targetId = decodeURIComponent(path.slice('/feed/profile/'.length))
    const cursor = toNumber(params.cursor, 0)
    const limit = Math.max(1, toNumber(params.limit, 10))
    const items = timelineOrder
      .map((id) => postRecords.get(id))
      .filter((post): post is PostRecord => {
        if (!post) {
          return false
        }
        return post.authorId === targetId
      })
      .sort((left, right) => right.publishTime - left.publishTime)
    const paged = items
      .slice(cursor, cursor + limit)
      .filter((post): post is PostRecord => Boolean(post))
      .map((post) => mapFeedItem(post, 'FOLLOWING'))
    const nextCursor = cursor + limit < items.length ? String(cursor + limit) : null
    return createResponse({ items: paged, nextCursor })
  }

  if (path === '/user/me/privacy') {
    const userId = requireCurrentUserId()
    return createResponse({
      needApproval: privacySettings.get(userId) ?? false
    })
  }

  if (path === '/auth/me') {
    return createResponse(authMePayload(requireCurrentUserId()))
  }

  if (path === '/comment/hot') {
    const postId = toString(params.postId)
    const rows = commentsByPost.get(postId) ?? []
    const limit = Math.max(1, toNumber(params.limit, 10))
    const preloadReplyLimit = Math.max(0, toNumber(params.preloadReplyLimit, 2))
    return createResponse({
      pinned: rows[0] ? mapRootCommentRow(rows[0], preloadReplyLimit) : null,
      items: rows.slice(1, 1 + limit).map((item) => mapRootCommentRow(item, preloadReplyLimit))
    })
  }

  if (path === '/interact/reaction/state') {
    const postId = toString(params.targetId)
    const post = postRecords.get(postId)
    if (!post) {
      throw new Error(`No UI mock post for reaction state: ${postId}`)
    }
    const userId = requireCurrentUserId()
    return createResponse({
      state: post.likedBy.has(userId),
      currentCount: post.likeCount
    })
  }

  if (path.startsWith('/content/publish/attempt/')) {
    const attemptId = decodeURIComponent(path.slice('/content/publish/attempt/'.length))
    const attempt = publishAttempts.get(attemptId)
    if (!attempt) {
      throw new Error(`No UI mock publish attempt for ${attemptId}`)
    }
    return createResponse(attempt)
  }

  if (path.startsWith('/content/schedule/')) {
    const taskId = decodeURIComponent(path.slice('/content/schedule/'.length))
    const task = scheduleTasks.get(taskId)
    if (!task) {
      throw new Error(`No UI mock schedule task for ${taskId}`)
    }
    return createResponse({
      taskId: task.taskId,
      userId: task.userId,
      scheduleTime: task.scheduleTime,
      status: task.status,
      retryCount: task.retryCount,
      isCanceled: task.isCanceled,
      lastError: task.lastError,
      alarmSent: task.alarmSent,
      contentData: task.contentData
    })
  }

  if (path.endsWith('/history')) {
    const postId = decodeURIComponent(path.slice('/content/'.length, -'/history'.length))
    const offset = Math.max(0, toNumber(params.offset, 0))
    const limit = Math.max(1, toNumber(params.limit, 20))
    const versions = contentHistories.get(postId) ?? []
    const items = versions.slice(offset, offset + limit)
    const nextCursor = offset + limit < versions.length ? offset + limit : null
    return createResponse({
      versions: items,
      nextCursor
    })
  }

  if (path.startsWith('/content/')) {
    const postId = decodeURIComponent(path.slice('/content/'.length))
    const post = postRecords.get(postId)
    if (!post) {
      throw new Error(`No UI mock content for ${postId}`)
    }
    return createResponse(mapContentDetail(post))
  }

  if (path === '/comment/list') {
    const postId = toString(params.postId)
    const rows = commentsByPost.get(postId) ?? []
    const cursor = toNumber(params.cursor, 0)
    const limit = Math.max(1, toNumber(params.limit, 20))
    const preloadReplyLimit = Math.max(0, toNumber(params.preloadReplyLimit, 2))

    const pinned = cursor === 0 && rows.length > 0 ? mapRootCommentRow(rows[0], preloadReplyLimit) : null
    const source = rows.slice(1)
    const items = source.slice(cursor, cursor + limit).map((item) => mapRootCommentRow(item, preloadReplyLimit))
    const nextCursor = cursor + limit < source.length ? String(cursor + limit) : null

    return createResponse({
      pinned,
      items,
      nextCursor
    })
  }

  if (path === '/comment/reply/list') {
    const rootId = toString(params.rootId)
    const root = findRootRecord(rootId)
    const cursor = toNumber(params.cursor, 0)
    const limit = Math.max(1, toNumber(params.limit, 20))
    const replies = root?.replies ?? []
    const items = replies.slice(cursor, cursor + limit).map(mapCommentRow)
    const nextCursor = cursor + limit < replies.length ? String(cursor + limit) : null
    return createResponse({ items, nextCursor })
  }

  if (path === '/search') {
    const q = toString(params.q).trim().toLowerCase()
    const size = Math.max(1, toNumber(params.size, 20))
    const after = toNumber(params.after, 0)

    const all = Array.from(postRecords.values())
      .filter((post) => {
        if (!q) return true
        const author = pickUser(post.authorId)
        return (
          post.title.toLowerCase().includes(q) ||
          post.text.toLowerCase().includes(q) ||
          post.summary.toLowerCase().includes(q) ||
          author.nickname.toLowerCase().includes(q) ||
          post.tags.some((tag) => tag.toLowerCase().includes(q))
        )
      })
      .sort((a, b) => b.publishTime - a.publishTime)

    const paged = all.slice(after, after + size)
    const items = paged.map((post) => {
      const author = pickUser(post.authorId)
      const viewerId = currentUserId
      return {
        id: post.postId,
        title: post.title,
        description: post.summary || post.text.slice(0, 140),
        coverImage: post.mediaUrls[0] ?? '',
        tags: post.tags,
        authorAvatar: author.avatarUrl,
        authorNickname: author.nickname,
        likeCount: post.likeCount,
        liked: viewerId ? post.likedBy.has(viewerId) : false
      }
    })
    const nextAfter = after + size < all.length ? String(after + size) : null

    return createResponse({
      items,
      nextAfter,
      hasMore: nextAfter !== null
    })
  }

  if (path === '/search/suggest') {
    const prefix = toString(params.prefix).trim().toLowerCase()
    const size = Math.max(1, toNumber(params.size, 10))
    const pool = new Set<string>()

    for (const post of postRecords.values()) {
      pool.add(post.title)
      pool.add(pickUser(post.authorId).nickname)
      post.tags.forEach((tag) => pool.add(tag))
    }

    const items = Array.from(pool)
      .filter((value) => (prefix ? value.toLowerCase().includes(prefix) : true))
      .slice(0, size)
    return createResponse({ items })
  }

  if (path === '/notification/list') {
    const cursor = toNumber(params.cursor, 0)
    const limit = 20
    const sliced = notifications
      .slice(cursor, cursor + limit)
      .map((item) => ({
        notificationId: item.notificationId,
        title: item.title,
        content: item.content,
        createTime: item.createTime,
        bizType: item.bizType,
        targetType: 'POST',
        targetId: item.targetId,
        postId: item.postId || null,
        rootCommentId: null,
        lastCommentId: null,
        lastActorUserId: item.lastActorUserId,
        unreadCount: item.unreadCount
      }))
    const nextCursor = cursor + limit < notifications.length ? String(cursor + limit) : null
    return createResponse({ notifications: sliced, nextCursor })
  }

  if (path === '/relation/followers' || path === '/relation/following') {
    const targetUserId = toString(params.userId, currentUserId ?? '')
    const cursor = toNumber(params.cursor, 0)
    const limit = Math.max(1, toNumber(params.limit, 20))

    const ids: string[] = []
    relationEdges.forEach((edge) => {
      const [sourceId, relationTargetId] = edge.split('->')
      if (path === '/relation/following' && sourceId === targetUserId) {
        ids.push(relationTargetId)
      }
      if (path === '/relation/followers' && relationTargetId === targetUserId) {
        ids.push(sourceId)
      }
    })

    const items = ids.slice(cursor, cursor + limit).map((id) => {
      const user = pickUser(id)
      return {
        userId: user.userId,
        nickname: user.nickname,
        avatar: user.avatarUrl,
        followTime: relationTimes.get(edgeKey(path === '/relation/following' ? targetUserId : id, path === '/relation/following' ? id : targetUserId)) ?? now
      }
    })
    const nextCursor = cursor + limit < ids.length ? String(cursor + limit) : null
    return createResponse({ items, nextCursor })
  }

  if (path === '/user/profile/page') {
    const targetUserId = toString(params.targetUserId, currentUserId ?? '')
    const user = pickUser(targetUserId)
    const viewerId = currentUserId
    const relation = {
      followCount: followCountOf(targetUserId),
      followerCount: followerCountOf(targetUserId),
      isFollow: viewerId ? isFollowing(viewerId, targetUserId) : false
    }
    const risk = risks.get(targetUserId) ?? { status: 'NORMAL', capabilities: ['POST', 'COMMENT'] }
    return createResponse({
      profile: {
        userId: user.userId,
        username: user.username,
        nickname: user.nickname,
        avatarUrl: user.avatarUrl,
        status: user.status
      },
      relation,
      risk
    })
  }

  if (path === '/user/profile') {
    const targetUserId = toString(params.targetUserId, currentUserId ?? '')
    const user = pickUser(targetUserId)
    return createResponse({
      userId: user.userId,
      username: user.username,
      nickname: user.nickname,
      avatarUrl: user.avatarUrl,
      status: user.status
    })
  }

  if (path === '/user/me/profile') {
    const user = pickUser(requireCurrentUserId())
    return createResponse({
      userId: user.userId,
      username: user.username,
      nickname: user.nickname,
      avatarUrl: user.avatarUrl,
      status: user.status
    })
  }

  if (path === '/risk/user/status') {
    return createResponse(risks.get(requireCurrentUserId()) ?? { status: 'NORMAL', capabilities: ['POST', 'COMMENT'] })
  }

  throw new Error(`No UI mock handler for [GET] ${path}`)
}

const handlePost = (path: string, body: Record<string, unknown>) => {
  if (path === '/auth/login/password') {
    const phone = toString(body.phone)
    const user = Array.from(users.values()).find((item) => item.phone === phone) ?? pickUser('1')
    currentUserId = user.userId
    return createResponse({
      userId: user.userId,
      tokenName: 'Authorization',
      tokenPrefix: 'Bearer',
      token: `mock-token-${user.userId}`,
      refreshToken: `mock-refresh-${user.userId}`
    })
  }

  if (path === '/auth/sms/send') {
    return createResponse({ expireSeconds: 300 })
  }

  if (path === '/auth/register') {
    const userId = String(users.size + 1)
    const phone = toString(body.phone, `1390000000${userId}`)
    const nickname = toString(body.nickname, `User ${userId}`)
    users.set(userId, {
      userId,
      phone,
      username: `user.${userId}`,
      nickname,
      avatarUrl: '',
      status: 'ACTIVE'
    })
    return createResponse({ userId })
  }

  if (path === '/auth/refresh') {
    return createResponse({
      userId: currentUserId,
      tokenName: 'Authorization',
      tokenPrefix: 'Bearer',
      token: `mock-token-${currentUserId}`,
      refreshToken: `mock-refresh-${currentUserId}`
    })
  }

  if (path === '/auth/logout') {
    currentUserId = null
    return createResponse(null)
  }

  if (path === '/auth/password/change') {
    const userId = requireCurrentUserId()
    const oldPassword = toString(body.oldPassword)
    const nextPassword = toString(body.newPassword)
    const currentPassword = passwordByUserId.get(userId)
    if (!nextPassword) {
      throw new Error('mock password change requires newPassword')
    }
    if (currentPassword !== oldPassword) {
      throw new Error('mock password change rejected old password')
    }
    passwordByUserId.set(userId, nextPassword)
    return createResponse(null)
  }

  if (path === '/interact/reaction') {
    const userId = requireCurrentUserId()
    const postId = toString(body.targetId)
    const action = toString(body.action).toUpperCase()
    const post = postRecords.get(postId)
    if (!post) {
      throw new Error(`No UI mock post for reaction: ${postId}`)
    }
    if (action === 'ADD') {
      if (!post.likedBy.has(userId)) {
        post.likedBy.add(userId)
        post.likeCount += 1
      }
    } else {
      if (post.likedBy.delete(userId)) {
        post.likeCount = Math.max(0, post.likeCount - 1)
      }
    }
    return createResponse({
      requestId: toString(body.requestId, `req-${Date.now()}`),
      currentCount: post.likeCount,
      success: true
    })
  }

  if (path === '/interact/comment') {
    const userId = requireCurrentUserId()
    const postId = toString(body.postId)
    const content = toString(body.content, '').trim()
    if (!postId || !content) {
      throw new Error('mock comment requires postId and content')
    }

    const rows = commentsByPost.get(postId) ?? []
    const parentId = toString(body.parentId)
    const commentId = `c-mock-${commentSeq++}`
    const createTime = Date.now()

    if (!parentId) {
      rows.unshift({
        root: {
          commentId,
          postId,
          userId,
          rootId: commentId,
          parentId: '',
          replyToId: '',
          content,
          status: 0,
          likeCount: 0,
          createTime
        },
        replies: []
      })
    } else {
      const root = findRootRecord(parentId) ?? rows[0] ?? null
      if (root) {
        root.replies.push({
          commentId,
          postId: root.root.postId,
          userId,
          rootId: root.root.commentId,
          parentId: root.root.commentId,
          replyToId: root.root.userId,
          content,
          status: 0,
          likeCount: 0,
          createTime
        })
      }
    }

    commentsByPost.set(postId, rows)
    return createResponse({
      commentId,
      createTime,
      status: 'OK'
    })
  }

  if (path === '/interact/comment/pin') {
    requireCurrentUserId()
    const postId = toString(body.postId)
    const commentId = toString(body.commentId)
    const rows = commentsByPost.get(postId) ?? []
    const targetIndex = rows.findIndex((item) => item.root.commentId === commentId)
    if (targetIndex < 0) {
      throw new Error(`No UI mock root comment for pin: ${commentId}`)
    }
    const [target] = rows.splice(targetIndex, 1)
    rows.unshift(target)
    commentsByPost.set(postId, rows)
    return createOperationResult(commentId, 'PINNED')
  }

  if (path === '/notification/read') {
    const id = toString(body.notificationId)
    const target = notifications.find((item) => item.notificationId === id)
    if (target) {
      target.unreadCount = 0
    }
    return createResponse({ success: true, id })
  }

  if (path === '/notification/read/all') {
    notifications.forEach((item) => {
      item.unreadCount = 0
    })
    return createResponse({ success: true })
  }

  if (path === '/relation/state/batch') {
    const viewerId = requireCurrentUserId()
    const ids = Array.isArray(body.targetUserIds) ? body.targetUserIds : []
    const normalizedIds = ids.map((id) => toString(id)).filter(Boolean)
    const followingUserIds = normalizedIds.filter((id) => isFollowing(viewerId, id))
    const blockedUserIds = normalizedIds.filter((id) => blockedEdges.has(edgeKey(viewerId, id)))
    return createResponse({
      followingUserIds,
      blockedUserIds
    })
  }

  if (path === '/relation/follow' || path === '/relation/unfollow') {
    const sourceId = toString(body.sourceId, requireCurrentUserId())
    const targetId = toString(body.targetId)
    const key = edgeKey(sourceId, targetId)
    if (!targetId) {
      throw new Error('mock relation action requires targetId')
    }
    if (path === '/relation/follow') {
      relationEdges.add(key)
      relationTimes.set(key, Date.now())
    } else {
      relationEdges.delete(key)
    }
    return createResponse({ success: true })
  }

  if (path === '/relation/block') {
    const sourceId = toString(body.sourceId, requireCurrentUserId())
    const targetId = toString(body.targetId)
    if (!targetId) {
      throw new Error('mock relation block requires targetId')
    }
    blockedEdges.add(edgeKey(sourceId, targetId))
    return createOperationResult(targetId, 'BLOCKED')
  }

  if (path === '/user/me/profile') {
    const userId = requireCurrentUserId()
    const user = pickUser(userId)
    const nickname = toString(body.nickname, user.nickname).trim()
    const avatarUrl = toString(body.avatarUrl, user.avatarUrl).trim()
    users.set(userId, {
      ...user,
      nickname: nickname || user.nickname,
      avatarUrl: avatarUrl || user.avatarUrl
    })
    return createResponse({ success: true, status: 'UPDATED' })
  }

  if (path === '/user/me/privacy') {
    const userId = requireCurrentUserId()
    privacySettings.set(userId, Boolean(body.needApproval))
    return createOperationResult(userId, 'UPDATED')
  }

  if (path === '/risk/appeals') {
    const appealId = `appeal-${appealSeq++}`
    return createResponse({ appealId, status: 'PENDING' })
  }

  if (path === '/media/upload/session') {
    const sessionId = `upload-${uploadSeq++}`
    return createResponse({
      uploadUrl: `mock://upload/${sessionId}`,
      token: `mock-upload-token-${sessionId}`,
      sessionId
    })
  }

  if (path === '/content/publish') {
    requireCurrentUserId()
    const requestedPostId = toString(body.postId)
    const postId = requestedPostId || `post-mock-${publishSeq++}`
    const existing = postRecords.get(postId)
    const title = toString(body.title, existing?.title ?? 'Untitled')
    const text = toString(body.text, existing?.text ?? '')
    const authorId = toString(body.userId, existing?.authorId ?? requireCurrentUserId())
    const mediaIds = parseMediaIds(
      (() => {
        const mediaInfo = body.mediaInfo
        if (typeof mediaInfo === 'string') {
          try {
            const parsed = JSON.parse(mediaInfo)
            return Array.isArray(parsed) ? parsed : []
          } catch {
            return []
          }
        }
        return []
      })()
    )
    const mediaUrls =
      body.mediaInfo === undefined
        ? [...(existing?.mediaUrls ?? [])]
        : mediaIds.map((id, index) => `https://picsum.photos/seed/${id || postId}-${index}/1200/900`)
    const versionNum = existing ? existing.versionNum + 1 : 1
    const publishTime = Date.now()

    postRecords.set(postId, {
      postId,
      authorId,
      title,
      summary: text ? text.slice(0, 120) : existing?.summary ?? '',
      text,
      mediaUrls,
      locationInfo: toString(body.location, existing?.locationInfo ?? 'Mock City'),
      publishTime,
      versionNum,
      likeCount: existing?.likeCount ?? 0,
      likedBy: existing?.likedBy ?? new Set(),
      tags: existing?.tags ?? ['published', 'mock']
    })
    if (!timelineOrder.includes(postId)) {
      timelineOrder.unshift(postId)
    }
    upsertHistoryVersion(postId, title, text, publishTime)

    const attemptId = `attempt-${postId}-${publishSeq++}`
    publishAttempts.set(attemptId, {
      attemptId,
      postId,
      userId: authorId,
      idempotentToken: `mock-idempotent-${postId}`,
      transcodeJobId: `mock-transcode-${postId}`,
      attemptStatus: 2,
      riskStatus: 1,
      transcodeStatus: 2,
      publishedVersionNum: versionNum,
      errorCode: '',
      errorMessage: '',
      createTime: publishTime,
      updateTime: publishTime
    })

    return createResponse({
      postId,
      attemptId,
      versionNum,
      status: 'PUBLISHED'
    })
  }

  if (path === '/content/schedule') {
    const userId = requireCurrentUserId()
    const postId = toString(body.postId)
    const taskId = `schedule-${scheduleSeq++}`
    const scheduleTime = toNumber(body.publishTime, Date.now())

    if (!postRecords.has(postId)) {
      throw new Error(`No UI mock content for schedule postId: ${postId}`)
    }

    scheduleTasks.set(taskId, {
      taskId,
      postId,
      userId,
      scheduleTime,
      status: 1,
      retryCount: 0,
      isCanceled: 0,
      lastError: '',
      alarmSent: 0,
      contentData: buildScheduleContentData(postId)
    })

    return createResponse({
      taskId,
      postId,
      status: 'SCHEDULED'
    })
  }

  if (path === '/content/schedule/cancel') {
    requireCurrentUserId()
    const taskId = toString(body.taskId)
    const task = scheduleTasks.get(taskId)
    if (!task) {
      throw new Error(`No UI mock schedule task for ${taskId}`)
    }
    task.isCanceled = 1
    task.status = 0
    return createOperationResult(taskId, 'CANCELED')
  }

  if (path.endsWith('/rollback')) {
    requireCurrentUserId()
    const postId = decodeURIComponent(path.slice('/content/'.length, -'/rollback'.length))
    const targetVersionId = toString(body.targetVersionId)
    const versions = contentHistories.get(postId) ?? []
    const target = versions.find((item) => item.versionId === targetVersionId)
    const post = postRecords.get(postId)
    if (!target || !post) {
      throw new Error(`No UI mock rollback target for ${postId}`)
    }
    post.title = target.title
    post.text = target.content
    post.summary = target.content.slice(0, 120)
    post.versionNum += 1
    upsertHistoryVersion(postId, post.title, post.text)
    return createOperationResult(postId, 'ROLLED_BACK')
  }

  throw new Error(`No UI mock handler for [POST] ${path}`)
}

const handlePut = (path: string, body: Record<string, unknown>) => {
  if (path === '/content/draft') {
    const draftId = toString(body.draftId, `draft-${draftSeq++}`)
    const userId = toString(body.userId, requireCurrentUserId())
    drafts.set(draftId, {
      draftId,
      userId,
      title: toString(body.title),
      contentText: toString(body.contentText),
      mediaIds: parseMediaIds(body.mediaIds)
    })
    return createResponse({ draftId })
  }

  throw new Error(`No UI mock handler for [PUT] ${path}`)
}

const handlePatch = (path: string, body: Record<string, unknown>) => {
  if (path.startsWith('/content/draft/')) {
    const draftId = decodeURIComponent(path.slice('/content/draft/'.length))
    const draft = drafts.get(draftId)
    if (!draft) {
      throw new Error(`No UI mock draft for ${draftId}`)
    }

    draft.title = toString(body.title, draft.title)
    draft.contentText = toString(body.diffContent, draft.contentText)
    const mediaIds = parseMediaIds(body.mediaIds)
    if (mediaIds.length > 0) {
      draft.mediaIds = mediaIds
    }

    return createResponse({
      serverVersion: String(toNumber(body.clientVersion, 0) + 1),
      syncTime: Date.now()
    })
  }

  if (path === '/content/schedule') {
    requireCurrentUserId()
    const taskId = toString(body.taskId)
    const task = scheduleTasks.get(taskId)
    if (!task) {
      throw new Error(`No UI mock schedule task for ${taskId}`)
    }

    if (body.publishTime !== undefined) {
      task.scheduleTime = toNumber(body.publishTime, task.scheduleTime)
    }
    if (body.contentData !== undefined) {
      task.contentData = toString(body.contentData, task.contentData)
    }
    task.status = 1

    return createResponse({
      success: true,
      status: 'UPDATED'
    })
  }

  throw new Error(`No UI mock handler for [PATCH] ${path}`)
}

const handleDelete = (path: string) => {
  if (path.startsWith('/comment/')) {
    requireCurrentUserId()
    const commentId = decodeURIComponent(path.slice('/comment/'.length))
    if (!removeCommentById(commentId)) {
      throw new Error(`No UI mock comment for ${commentId}`)
    }
    return createOperationResult(commentId, 'DELETED')
  }

  if (path.startsWith('/content/')) {
    requireCurrentUserId()
    const postId = decodeURIComponent(path.slice('/content/'.length))
    if (!postRecords.delete(postId)) {
      throw new Error(`No UI mock content for ${postId}`)
    }
    const index = timelineOrder.indexOf(postId)
    if (index >= 0) {
      timelineOrder.splice(index, 1)
    }
    drafts.delete(postId)
    contentHistories.delete(postId)
    for (const [taskId, task] of scheduleTasks.entries()) {
      if (task.postId === postId) {
        scheduleTasks.delete(taskId)
      }
    }
    return createOperationResult(postId, 'DELETED')
  }

  throw new Error(`No UI mock handler for [DELETE] ${path}`)
}

export const mockRequest = async <T>(
  method: MockMethod,
  url: string,
  options?: MockRequestOptions
): Promise<T> => {
  const path = toPath(url)
  const params = {
    ...toQueryParams(url),
    ...toRecord(options?.params)
  }
  const body = toRecord(options?.data)
  const normalizedMethod = method.toLowerCase() as MockMethod

  let result: unknown
  switch (normalizedMethod) {
    case 'get':
      result = handleGet(path, params)
      break
    case 'post':
      result = handlePost(path, body)
      break
    case 'put':
      result = handlePut(path, body)
      break
    case 'patch':
      result = handlePatch(path, body)
      break
    case 'delete':
      result = handleDelete(path)
      break
    default:
      throw new Error(`No UI mock handler for [${method.toUpperCase()}] ${path}`)
  }

  return result as T
}
