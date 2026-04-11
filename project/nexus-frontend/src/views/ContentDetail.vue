<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { fetchContentDetail, type ContentDetailViewModel } from '@/api/content'
import {
  fetchComments,
  fetchCommentReplies,
  postComment,
  postReaction,
  type CommentDisplayItem,
  type RootCommentDisplayItem
} from '@/api/interact'
import PrototypeCommentComposer from '@/components/content/PrototypeCommentComposer.vue'
import PrototypeContinuationGrid, {
  type PrototypeContinuationCard
} from '@/components/content/PrototypeContinuationGrid.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeReadingColumn from '@/components/prototype/PrototypeReadingColumn.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'

interface ReplyThreadState {
  expanded: boolean
  loading: boolean
  loaded: boolean
  items: CommentDisplayItem[]
  nextCursor: string | null
  hasMore: boolean
}

interface ContinuationCard extends PrototypeContinuationCard {
  target: 'author' | 'home'
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const detail = ref<ContentDetailViewModel | null>(null)
const pinnedComment = ref<RootCommentDisplayItem | null>(null)
const comments = ref<RootCommentDisplayItem[]>([])
const nextCursor = ref<string | null>(null)
const hasMoreComments = ref(false)
const replyThreads = ref<Record<string, ReplyThreadState>>({})
const loading = ref(true)
const commentLoading = ref(false)
const sending = ref(false)
const errorMsg = ref('')
const commentError = ref('')
const commentContent = ref('')
const liked = ref(false)
const likeCount = ref(0)
const commentSort = ref<'popular' | 'newest'>('popular')
const shareNotice = ref('')

const heroFallback =
  'https://lh3.googleusercontent.com/aida-public/AB6AXuAP584_spX38oDYtgxnY8LCloWPjTF603ZRYXC26L17S_RQjADoaiA_OAtYEgTrgBPnsqI_p3vmG4bdJejPNMURCe4p5v9N3fvBYbv-OuoF56Heu1VCf0qbnzAYp_iuldcfLm1Wv1_pfn9sUdkY8COx8QsSSRPL6AzV9b09fJuuorrudouflgowpcSJbNXeJMxtvlek1huXwhUOEdXQZ40iCLNw-yXhYvJ0hxXl9KJT9uIFze-rHrRhyTsHxmnHdS9j2-swvAUCRgEe'
const cardFallback =
  'https://lh3.googleusercontent.com/aida-public/AB6AXuAIVVaii-FOi9Jgjmrqt52BoQ6JGCi6zy8Wq0yusB2_92x8ADfcts72NF8EJWWPBrpFplUj7o74flB4n1QENt1Z3ALfzd_Y1HWKRMe34w5NWGiG8UAJd_m4dkXYHytQRzBhb5im3rtqKIA4Cgj5MLy0C9ic7NiWk4Yyp_7qE0qAqZiksXQJoq1pEAUy0NKf4g9zevGhSXYAkN0uqJ0QLXQu4zawESdfFQCakuyQLfRgL2njiP1ddwUM-sIdkNFfHcgQtFlrrm695rjk'
const avatarFallback =
  'https://lh3.googleusercontent.com/aida-public/AB6AXuAqdqAP_p2weYMVOi7BV3nNhuYLRPAl8yf0BGDIdXOk2ie6K7vU-CxtTTFuT1U-33n9wY--26YcGr0BIcmmK1s16R3bU1XLKXm3khqroKj_t-dOI4BayOo_UPKsLbTqbdj85aD3hQNnEGPKj87OtyJE7iCNkuRy448Gdfk16PcgAYULIyAvmy-7xGZaaMFjwzdcPXHNMlf9PjONfgm73ebfRUE1N6nn-TmtduFNbXjYh64x4nQv4i3PmmyyVQqB3EsZi1OdN9pczxDO'

const postId = computed(() => String(route.params.postId || ''))

const formatAbsoluteDate = (timestamp?: number) => {
  if (!timestamp) return 'RECENTLY'
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return 'RECENTLY'
  return date
    .toLocaleDateString('en-US', {
      month: 'long',
      year: 'numeric'
    })
    .toUpperCase()
}

const formatRelativeDate = (timestamp?: number) => {
  if (!timestamp) return 'Just now'
  const diff = Date.now() - timestamp
  const minutes = Math.max(1, Math.floor(diff / 60000))
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return new Date(timestamp).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric'
  })
}

const splitNarrative = (content: string, summary: string) => {
  const paragraphs = content
    .split(/\n+/)
    .map((item) => item.trim())
    .filter(Boolean)

  if (paragraphs.length > 0) {
    return {
      intro: summary.trim() || paragraphs[0],
      body: summary.trim() ? paragraphs.slice(0, 4) : paragraphs.slice(1, 5)
    }
  }

  const sentences = content
    .split(/(?<=[。！？.!?])\s+/)
    .map((item) => item.trim())
    .filter(Boolean)

  return {
    intro: summary.trim() || sentences[0] || 'This story is still being composed.',
    body: sentences.slice(1, 5)
  }
}

const narrative = computed(() => {
  if (!detail.value) {
    return {
      intro: '',
      body: []
    }
  }

  return splitNarrative(detail.value.content, detail.value.summary)
})

const detailTitle = computed(() => detail.value?.title || 'Untitled Narrative')
const detailAuthor = computed(() => detail.value?.authorName || 'Unknown Author')
const heroImage = computed(() => detail.value?.mediaUrls[0] || heroFallback)
const publishedLabel = computed(() => formatAbsoluteDate(detail.value?.createTime))
const detailTags = computed(() => {
  if (!detail.value) return []

  const tags = [
    detail.value.locationInfo,
    detail.value.edited ? 'Edited' : 'Published',
    detail.value.versionNum > 0 ? `Version ${detail.value.versionNum}` : '',
    detail.value.mediaUrls.length > 1 ? `${detail.value.mediaUrls.length} Frames` : ''
  ]

  return tags.filter(Boolean).slice(0, 4)
})

const continuationCards = computed<ContinuationCard[]>(() => {
  if (!detail.value) return []

  return [
    {
      id: 'author',
      title: `More from ${detailAuthor.value}`,
      subtitle: detail.value.locationInfo || 'Visit the author archive and continue this thread of work.',
      image: detail.value.mediaUrls[1] || heroImage.value,
      target: 'author',
      wide: true
    },
    {
      id: 'home',
      title: 'Return to the Gallery',
      subtitle: 'Continue through the broader editorial feed without fabricating related items.',
      image: detail.value.mediaUrls[2] || cardFallback,
      target: 'home'
    }
  ]
})

const sortedComments = computed(() => {
  const items = [...comments.value]
  return items.sort((left, right) => {
    if (commentSort.value === 'newest') {
      return right.createTime - left.createTime
    }
    if (right.likeCount === left.likeCount) {
      return right.createTime - left.createTime
    }
    return right.likeCount - left.likeCount
  })
})

const commentTotal = computed(() => {
  const pinned = pinnedComment.value ? 1 : 0
  const roots = comments.value.length
  const replies = Object.values(replyThreads.value).reduce((total, thread) => total + thread.items.length, 0)
  return pinned + roots + replies
})

const mergeRootComments = (currentItems: RootCommentDisplayItem[], incomingItems: RootCommentDisplayItem[]) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) return false
    seen.add(item.commentId)
    return true
  })
  return [...currentItems, ...nextItems]
}

const mergeReplies = (currentItems: CommentDisplayItem[], incomingItems: CommentDisplayItem[]) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) return false
    seen.add(item.commentId)
    return true
  })
  return [...currentItems, ...nextItems]
}

const ensureReplyThread = (root: RootCommentDisplayItem): ReplyThreadState => {
  if (!replyThreads.value[root.commentId]) {
    replyThreads.value[root.commentId] = {
      expanded: false,
      loading: false,
      loaded: false,
      items: [...root.repliesPreview],
      nextCursor: null,
      hasMore: root.replyCount > root.repliesPreview.length
    }
  }
  return replyThreads.value[root.commentId]
}

const getReplyThread = (root: RootCommentDisplayItem) => ensureReplyThread(root)

const loadDetail = async () => {
  if (!postId.value) {
    errorMsg.value = '缺少内容标识，无法加载详情'
    detail.value = null
    loading.value = false
    return
  }

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await fetchContentDetail(postId.value, authStore.userId || undefined)
    detail.value = res
    likeCount.value = res.likeCount
    liked.value = false
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '内容加载失败'
    detail.value = null
  } finally {
    loading.value = false
  }
}

const loadComments = async (reset = true) => {
  if (!postId.value) return

  commentLoading.value = true
  try {
    const res = await fetchComments({
      postId: postId.value,
      cursor: reset ? undefined : nextCursor.value || undefined,
      limit: 20,
      preloadReplyLimit: 2
    })

    pinnedComment.value = res.pinned
    comments.value = reset ? res.items : mergeRootComments(comments.value, res.items)
    nextCursor.value = res.page.nextCursor
    hasMoreComments.value = res.page.hasMore

    if (reset) {
      replyThreads.value = {}
    }
  } catch (error) {
    console.error('fetch comments failed', error)
    if (reset) {
      pinnedComment.value = null
      comments.value = []
      nextCursor.value = null
      hasMoreComments.value = false
      replyThreads.value = {}
    }
  } finally {
    commentLoading.value = false
  }
}

const loadReplies = async (root: RootCommentDisplayItem, append = false) => {
  const thread = ensureReplyThread(root)
  if (thread.loading || (append && !thread.hasMore)) return

  thread.loading = true
  try {
    const res = await fetchCommentReplies({
      rootId: root.commentId,
      cursor: append ? thread.nextCursor || undefined : undefined,
      limit: 20
    })

    thread.items = append ? mergeReplies(thread.items, res.items) : mergeReplies(root.repliesPreview, res.items)
    thread.nextCursor = res.page.nextCursor
    thread.hasMore = res.page.hasMore
    thread.loaded = true
  } catch (error) {
    console.error('fetch replies failed', error)
  } finally {
    thread.loading = false
  }
}

const handleLike = async () => {
  const previousLiked = liked.value
  const previousCount = likeCount.value

  liked.value = !liked.value
  likeCount.value = liked.value ? likeCount.value + 1 : Math.max(0, likeCount.value - 1)

  try {
    await postReaction({
      requestId: `detail_like_${Date.now()}`,
      targetId: postId.value,
      targetType: 'POST',
      type: 'LIKE',
      action: liked.value ? 'ADD' : 'REMOVE'
    })
  } catch (error) {
    liked.value = previousLiked
    likeCount.value = previousCount
    console.error('like failed', error)
  }
}

const toggleReplies = async (root: RootCommentDisplayItem) => {
  const thread = ensureReplyThread(root)
  thread.expanded = !thread.expanded
  if (thread.expanded && !thread.loaded) {
    await loadReplies(root)
  }
}

const handlePostComment = async () => {
  const content = commentContent.value.trim()
  if (!content) return

  sending.value = true
  commentError.value = ''

  const optimisticCommentId = `local-${Date.now()}`
  const optimisticComment: RootCommentDisplayItem = {
    commentId: optimisticCommentId,
    postId: postId.value,
    userId: '',
    authorName: '我',
    authorAvatar: '',
    rootId: optimisticCommentId,
    parentId: '',
    replyToId: '',
    content,
    status: 0,
    likeCount: 0,
    replyCount: 0,
    createTime: Date.now(),
    repliesPreview: []
  }

  comments.value = [optimisticComment, ...comments.value]
  commentContent.value = ''

  try {
    await postComment({ postId: postId.value, content })
    await loadComments(true)
  } catch (error) {
    console.error('post comment failed', error)
    comments.value = comments.value.filter((item) => item.commentId !== optimisticCommentId)
    commentContent.value = content
    commentError.value = error instanceof Error ? error.message : '评论发送失败'
  } finally {
    sending.value = false
  }
}

const loadAll = async () => {
  await Promise.all([loadDetail(), loadComments(true)])
}

const navTo = (path: string) => {
  void router.push(path)
}

const openContinuation = (target: ContinuationCard['target']) => {
  if (target === 'author' && detail.value?.authorId) {
    void router.push(`/user/${detail.value.authorId}`)
    return
  }

  void router.push('/')
}

const shareCurrent = async () => {
  const link = window.location.href
  shareNotice.value = ''

  try {
    await navigator.clipboard.writeText(link)
    shareNotice.value = 'Link copied to clipboard.'
    window.setTimeout(() => {
      shareNotice.value = ''
    }, 2400)
  } catch (error) {
    console.error('share failed', error)
    shareNotice.value = 'Unable to copy the link automatically.'
  }
}

onMounted(() => {
  void loadAll()
})

watch(
  () => route.params.postId,
  () => {
    void loadAll()
  }
)
</script>

<template>
  <PrototypeShell>
    <PrototypeContainer v-if="loading" class="pt-12">
      <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface px-8 py-20 text-center">
        <div class="mx-auto mb-8 inline-flex h-20 w-20 items-center justify-center rounded-full border border-prototype-line">
          <span class="material-symbols-outlined text-prototype-muted">hourglass_top</span>
        </div>
        <h1 class="mb-4 font-headline text-4xl tracking-[-0.04em] text-prototype-ink">Preparing the narrative</h1>
        <p class="mx-auto max-w-xl text-sm leading-7 text-prototype-muted">
          正在注入正文、图片和评论线程，页面结构保持原型节奏。
        </p>
      </section>
    </PrototypeContainer>

    <PrototypeContainer v-else-if="errorMsg" class="pt-12">
      <section class="rounded-[2rem] border border-prototype-line bg-prototype-surface px-8 py-20 text-center">
        <div class="mx-auto mb-8 inline-flex h-20 w-20 items-center justify-center rounded-full border border-prototype-line bg-prototype-bg">
          <span class="material-symbols-outlined text-prototype-accent" style="font-variation-settings: 'FILL' 1;">lock</span>
        </div>
        <h1 class="mb-4 font-headline text-4xl tracking-[-0.04em] text-prototype-ink">This story is temporarily unavailable</h1>
        <p class="mx-auto mb-10 max-w-xl text-sm leading-7 text-prototype-muted">
          {{ errorMsg }}
        </p>
        <div class="flex flex-col items-center justify-center gap-4 sm:flex-row">
          <button
            type="button"
            class="rounded-full bg-prototype-ink px-8 py-3 text-sm font-semibold text-prototype-surface transition hover:opacity-90"
            @click="navTo('/')"
          >
            Return to Gallery
          </button>
          <button
            type="button"
            class="text-sm font-semibold text-prototype-accent transition hover:text-prototype-ink"
            @click="loadAll"
          >
            Retry
          </button>
        </div>
      </section>
    </PrototypeContainer>

    <article v-else-if="detail" data-prototype-detail class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <header class="flex flex-col gap-8 md:flex-row md:items-end md:justify-between">
          <div class="space-y-4">
            <button
              type="button"
              class="text-xs font-semibold uppercase tracking-[0.24em] text-prototype-muted transition hover:text-prototype-ink"
              @click="router.back()"
            >
              Back
            </button>
            <div class="space-y-3">
              <h1 class="max-w-[14ch] font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
                {{ detailTitle }}
              </h1>
              <div class="flex flex-wrap items-center gap-3 text-sm text-prototype-muted">
                <span class="font-semibold tracking-[0.08em]">{{ detailAuthor.toUpperCase() }}</span>
                <span class="h-1 w-1 rounded-full bg-prototype-line" />
                <span class="text-[11px] uppercase tracking-[0.24em]">{{ publishedLabel }}</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-3 self-start rounded-full border border-prototype-line bg-prototype-surface px-4 py-3 md:self-auto">
            <button type="button" class="flex items-center gap-2 transition" @click="handleLike">
              <span class="material-symbols-outlined text-prototype-accent transition-transform" :class="liked ? 'scale-110' : ''">
                favorite
              </span>
              <span class="text-lg font-semibold text-prototype-ink">{{ likeCount }}</span>
            </button>
            <button
              type="button"
              class="rounded-full border border-prototype-line px-3 py-1.5 text-xs font-semibold text-prototype-muted transition hover:text-prototype-ink"
              @click="shareCurrent"
            >
              Share
            </button>
          </div>
        </header>
      </PrototypeContainer>

      <PrototypeContainer width="content">
        <section class="relative aspect-[16/10] overflow-hidden rounded-[1.75rem] bg-prototype-surface">
          <img :src="heroImage" :alt="detailTitle" class="h-full w-full object-cover">
        </section>
      </PrototypeContainer>

      <PrototypeReadingColumn>
        <p class="text-xl italic leading-9 text-prototype-ink">
          {{ narrative.intro }}
        </p>

        <div class="space-y-6">
          <p
            v-for="(paragraph, index) in narrative.body"
            :key="`${detail.postId}-paragraph-${index}`"
            class="text-base leading-8 text-prototype-muted"
          >
            {{ paragraph }}
          </p>
        </div>

        <div v-if="detailTags.length > 0" class="flex flex-wrap gap-2 pt-2">
          <span
            v-for="tag in detailTags"
            :key="tag"
            class="rounded-full border border-prototype-line px-4 py-2 text-xs font-semibold uppercase tracking-[0.16em] text-prototype-muted"
          >
            {{ tag }}
          </span>
        </div>
      </PrototypeReadingColumn>

      <PrototypeContainer width="content">
        <div class="border-t border-prototype-line pt-16">
          <PrototypeContinuationGrid
            :cards="continuationCards"
            @browse="navTo('/')"
            @select="(cardId) => openContinuation(cardId === 'author' ? 'author' : 'home')"
          />
        </div>
      </PrototypeContainer>

      <section id="comments" data-prototype-comments class="space-y-8">
        <PrototypeReadingColumn>
          <div class="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
            <div class="flex items-baseline gap-3">
              <h2 class="font-headline text-3xl tracking-[-0.03em] text-prototype-ink md:text-4xl">Community Notes</h2>
              <span class="font-medium text-prototype-muted">{{ commentTotal }}</span>
            </div>

            <div class="flex rounded-full border border-prototype-line p-1">
              <button
                type="button"
                class="rounded-full px-4 py-1.5 text-xs transition"
                :class="commentSort === 'popular' ? 'bg-prototype-ink font-semibold text-prototype-surface' : 'font-semibold text-prototype-muted hover:text-prototype-ink'"
                @click="commentSort = 'popular'"
              >
                Popular
              </button>
              <button
                type="button"
                class="rounded-full px-4 py-1.5 text-xs transition"
                :class="commentSort === 'newest' ? 'bg-prototype-ink font-semibold text-prototype-surface' : 'font-semibold text-prototype-muted hover:text-prototype-ink'"
                @click="commentSort = 'newest'"
              >
                Newest
              </button>
            </div>
          </div>

          <PrototypeCommentComposer
            v-model="commentContent"
            :avatar-src="avatarFallback"
            :sending="sending"
            @submit="handlePostComment"
          />

          <p v-if="shareNotice" class="text-sm font-medium text-prototype-accent">{{ shareNotice }}</p>
          <p v-if="commentError" class="text-sm font-medium text-error">{{ commentError }}</p>

          <div
            v-if="pinnedComment"
            class="relative overflow-hidden rounded-[1.5rem] border border-prototype-line bg-prototype-surface p-6"
          >
            <div class="absolute left-0 top-0 flex items-center gap-1 rounded-br-xl bg-prototype-ink px-3 py-1 text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-surface">
              <span class="material-symbols-outlined text-[12px]" style="font-variation-settings: 'FILL' 1;">push_pin</span>
              Pinned
            </div>

            <article class="mt-5 flex gap-5">
              <div class="h-10 w-10 shrink-0 overflow-hidden rounded-full bg-prototype-bg">
                <img :src="pinnedComment.authorAvatar || avatarFallback" :alt="pinnedComment.authorName" class="h-full w-full object-cover">
              </div>
              <div class="flex-1">
                <div class="mb-2 flex items-baseline justify-between gap-4">
                  <span class="font-semibold text-prototype-ink">{{ pinnedComment.authorName }}</span>
                  <span class="text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-muted/70">
                    {{ formatRelativeDate(pinnedComment.createTime) }}
                  </span>
                </div>
                <p class="mb-4 text-sm leading-7 text-prototype-ink">{{ pinnedComment.content }}</p>
                <div class="flex items-center gap-6">
                  <div class="flex items-center gap-1.5 text-prototype-accent">
                    <span class="material-symbols-outlined text-sm" style="font-variation-settings: 'FILL' 1;">favorite</span>
                    <span class="text-xs font-semibold">{{ pinnedComment.likeCount }}</span>
                  </div>
                  <button type="button" class="text-xs font-semibold uppercase tracking-[0.2em] text-prototype-muted transition hover:text-prototype-ink">
                    Reply
                  </button>
                </div>
              </div>
            </article>
          </div>

          <div
            v-if="commentLoading && !pinnedComment && comments.length === 0"
            class="rounded-[1.5rem] border border-prototype-line bg-prototype-surface px-8 py-14 text-center"
          >
            <h3 class="mb-3 font-headline text-2xl tracking-[-0.02em] text-prototype-ink">Loading community notes</h3>
            <p class="text-sm leading-7 text-prototype-muted">讨论区内容正在同步中。</p>
          </div>

          <div
            v-else-if="!pinnedComment && comments.length === 0"
            class="rounded-[1.5rem] border border-prototype-line bg-prototype-surface px-8 py-14 text-center"
          >
            <h3 class="mb-3 font-headline text-2xl tracking-[-0.02em] text-prototype-ink">Quiet for now</h3>
            <p class="text-sm leading-7 text-prototype-muted">还没有评论，成为第一个留下想法的人。</p>
          </div>

          <div v-else class="space-y-12">
            <div v-for="item in sortedComments" :key="item.commentId" class="space-y-8 border-b border-prototype-line pb-10 last:border-b-0 last:pb-0">
              <article class="flex gap-5">
                <div class="h-10 w-10 shrink-0 overflow-hidden rounded-full bg-prototype-bg">
                  <img :src="item.authorAvatar || avatarFallback" :alt="item.authorName" class="h-full w-full object-cover">
                </div>
                <div class="flex-1">
                  <div class="mb-2 flex items-baseline justify-between gap-4">
                    <span class="font-semibold text-prototype-ink">{{ item.authorName }}</span>
                    <span class="text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-muted/70">
                      {{ formatRelativeDate(item.createTime) }}
                    </span>
                  </div>
                  <p class="mb-4 text-sm leading-7 text-prototype-muted">{{ item.content }}</p>
                  <div class="flex flex-wrap items-center gap-6">
                    <div class="flex items-center gap-1.5 text-prototype-muted">
                      <span class="material-symbols-outlined text-sm">favorite</span>
                      <span class="text-xs font-semibold">{{ item.likeCount }}</span>
                    </div>
                    <button
                      v-if="item.replyCount > 0"
                      type="button"
                      class="text-xs font-semibold uppercase tracking-[0.2em] text-prototype-muted transition hover:text-prototype-ink"
                      @click="toggleReplies(item)"
                    >
                      {{ getReplyThread(item).expanded ? 'Hide Replies' : `View Replies (${item.replyCount})` }}
                    </button>
                  </div>
                </div>
              </article>

              <div v-if="item.repliesPreview.length > 0 || getReplyThread(item).expanded" class="space-y-8 pl-14">
                <article
                  v-for="reply in getReplyThread(item).expanded ? getReplyThread(item).items : item.repliesPreview"
                  :key="reply.commentId"
                  class="flex gap-5"
                >
                  <div class="h-8 w-8 shrink-0 overflow-hidden rounded-full bg-prototype-bg">
                    <img :src="reply.authorAvatar || avatarFallback" :alt="reply.authorName" class="h-full w-full object-cover">
                  </div>
                  <div class="flex-1">
                    <div class="mb-2 flex items-baseline justify-between gap-4">
                      <span class="flex items-center gap-2 font-semibold text-prototype-ink">
                        {{ reply.authorName }}
                        <span
                          v-if="reply.userId === detail.authorId"
                          class="rounded-full border border-prototype-line px-2 py-0.5 text-[9px] uppercase tracking-[0.16em] text-prototype-muted"
                        >
                          Author
                        </span>
                      </span>
                      <span class="text-[10px] font-bold uppercase tracking-[0.2em] text-prototype-muted/70">
                        {{ formatRelativeDate(reply.createTime) }}
                      </span>
                    </div>
                    <p class="mb-4 text-sm leading-7 text-prototype-muted">{{ reply.content }}</p>
                    <div class="flex items-center gap-6">
                      <div class="flex items-center gap-1.5 text-prototype-muted">
                        <span class="material-symbols-outlined text-sm">favorite</span>
                        <span class="text-xs font-semibold">{{ reply.likeCount }}</span>
                      </div>
                    </div>
                  </div>
                </article>

                <button
                  v-if="getReplyThread(item).expanded && getReplyThread(item).hasMore"
                  type="button"
                  class="text-xs font-semibold uppercase tracking-[0.2em] text-prototype-muted transition hover:text-prototype-ink"
                  :disabled="getReplyThread(item).loading"
                  @click="loadReplies(item, true)"
                >
                  {{ getReplyThread(item).loading ? 'Loading...' : 'Load more replies' }}
                </button>
              </div>
            </div>

            <button
              v-if="hasMoreComments"
              type="button"
              class="w-full border-t border-prototype-line py-4 text-xs font-semibold uppercase tracking-[0.2em] text-prototype-muted transition hover:text-prototype-ink"
              :disabled="commentLoading"
              @click="loadComments(false)"
            >
              {{ commentLoading ? 'Loading...' : 'Load more perspective' }}
            </button>
          </div>
        </PrototypeReadingColumn>
      </section>
    </article>
  </PrototypeShell>
</template>
