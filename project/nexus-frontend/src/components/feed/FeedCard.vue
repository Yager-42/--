<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { reactToTarget } from '@/services/api/interactionApi'
import { usePostInteractionsStore } from '@/stores/postInteractions'
import type { FeedCardViewModel } from '@/types/viewModels'

const props = withDefaults(
  defineProps<{
    item: FeedCardViewModel
    routeMode?: 'detail' | 'edit'
    showOwnerActions?: boolean
  }>(),
  {
    routeMode: 'detail',
    showOwnerActions: false
  }
)

const emit = defineEmits<{
  (event: 'edit'): void
  (event: 'delete'): void
}>()

const isMenuOpen = ref(false)
const isReacting = ref(false)
const liked = ref(false)
const likeCountLabel = ref('0')
const postInteractionsStore = usePostInteractionsStore()

watch(
  () => props.item,
  (item) => {
    postInteractionsStore.primeInteraction(item.id, {
      liked: Boolean(item.liked),
      likeCountLabel: item.likeCountLabel
    })
    const cachedInteraction = postInteractionsStore.getInteraction(item.id)

    liked.value = cachedInteraction?.liked ?? Boolean(item.liked)
    likeCountLabel.value = cachedInteraction?.likeCountLabel ?? item.likeCountLabel
  },
  { immediate: true, deep: true }
)

const targetRoute = computed(() => {
  if (props.routeMode === 'edit') {
    return `/compose/editor?postId=${props.item.id}`
  }

  return `/post/${props.item.id}`
})

function parseNumeric(value: string) {
  return Number(value.replace(/\D/g, '') || '0')
}

async function handleToggleLike() {
  if (isReacting.value) {
    return
  }

  isReacting.value = true

  try {
    const nextLiked = !liked.value
    const currentCount = parseNumeric(likeCountLabel.value)
    const result = await reactToTarget({
      requestId: `feed-${props.item.id}-${Date.now()}`,
      targetId: parseNumeric(props.item.id),
      targetType: 'POST',
      type: 'LIKE',
      action: nextLiked ? 'ADD' : 'REMOVE'
    })

    liked.value = nextLiked
    likeCountLabel.value = String(result.currentCount ?? (nextLiked ? currentCount + 1 : Math.max(currentCount - 1, 0)))
    postInteractionsStore.updateInteraction(props.item.id, {
      liked: liked.value,
      likeCountLabel: likeCountLabel.value
    })
  } finally {
    isReacting.value = false
  }
}

function handleEdit() {
  isMenuOpen.value = false
  emit('edit')
}

function handleDelete() {
  isMenuOpen.value = false
  emit('delete')
}
</script>

<template>
  <article
    class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)] transition hover:-translate-y-0.5 hover:border-nx-primary"
  >
    <div class="flex items-start justify-between gap-4">
      <RouterLink
        :data-test="`feed-card-link-${item.id}`"
        :to="targetRoute"
        class="block min-w-0 flex-1"
      >
        <header class="flex items-center justify-between gap-4">
          <div class="min-w-0">
            <p class="truncate font-medium text-nx-text">{{ item.authorName }}</p>
            <p class="text-sm text-nx-text-muted">{{ item.publishTimeLabel || 'Editorial Social feed' }}</p>
          </div>
          <span class="shrink-0 rounded-full bg-nx-surface-muted px-3 py-1 text-xs font-medium text-nx-text-muted">
            {{ likeCountLabel }} 赞
          </span>
        </header>

        <p class="mt-4 text-base leading-7 text-nx-text">{{ item.summary }}</p>
        <p v-if="item.body" class="mt-3 text-sm leading-7 text-nx-text-muted">{{ item.body }}</p>
      </RouterLink>

      <div v-if="showOwnerActions" class="relative shrink-0">
        <button
          :data-test="`feed-card-menu-${item.id}`"
          type="button"
          class="inline-flex h-10 min-w-10 items-center justify-center rounded-full border border-nx-border bg-white px-3 text-sm font-semibold text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
          @click="isMenuOpen = !isMenuOpen"
        >
          ...
        </button>

        <div
          v-if="isMenuOpen"
          :data-test="`feed-card-actions-${item.id}`"
          class="absolute right-0 top-12 z-10 w-36 rounded-3xl border border-nx-border bg-white p-2 shadow-[var(--nx-shadow-card)]"
        >
          <button
            :data-test="`feed-card-edit-${item.id}`"
            type="button"
            class="flex min-h-10 w-full items-center rounded-2xl px-3 text-sm font-medium text-nx-text transition hover:bg-nx-surface-muted"
            @click="handleEdit"
          >
            编辑内容
          </button>
          <button
            :data-test="`feed-card-delete-${item.id}`"
            type="button"
            class="mt-1 flex min-h-10 w-full items-center rounded-2xl px-3 text-sm font-medium text-red-600 transition hover:bg-red-50"
            @click="handleDelete"
          >
            删除内容
          </button>
        </div>
      </div>
    </div>

    <div class="mt-5 flex items-center justify-between gap-3 border-t border-nx-border pt-4">
      <button
        :data-test="`feed-card-like-${item.id}`"
        type="button"
        class="inline-flex min-h-11 items-center justify-center rounded-full border px-4 text-sm font-medium transition"
        :class="liked ? 'border-nx-primary bg-blue-50 text-nx-primary' : 'border-nx-border text-nx-text'"
        :disabled="isReacting"
        @click="handleToggleLike"
      >
        {{ liked ? '已赞' : '点赞' }}
      </button>

      <RouterLink
        :data-test="`feed-card-open-${item.id}`"
        :to="targetRoute"
        class="text-sm font-medium text-nx-primary transition hover:opacity-80"
      >
        {{ routeMode === 'edit' ? '继续编辑' : '查看详情' }}
      </RouterLink>
    </div>
  </article>
</template>
