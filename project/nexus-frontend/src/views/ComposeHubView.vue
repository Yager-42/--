<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import TimelineList from '@/components/feed/TimelineList.vue'
import { fetchProfileTimeline } from '@/services/api/feedApi'
import { fetchMyProfileViewModel } from '@/services/api/profileApi'
import { useComposerStore } from '@/stores/composer'
import type { FeedCardViewModel } from '@/types/viewModels'

const router = useRouter()
const composerStore = useComposerStore()

const isLoading = ref(false)
const publishedPosts = ref<FeedCardViewModel[]>([])

const hasWorkspaceDraft = computed(() => {
  return Boolean(composerStore.draft.draftId || composerStore.draft.title || composerStore.draft.body)
})

async function openNewDraft() {
  composerStore.startNewDraft()
  await router.push('/compose/editor')
}

async function openWorkspaceDraft() {
  await router.push('/compose/editor')
}

async function openPublishedPost(postId: string) {
  await composerStore.hydrateFromPost(postId)
  await router.push(`/compose/editor?postId=${postId}`)
}

onMounted(async () => {
  isLoading.value = true

  try {
    const profile = await fetchMyProfileViewModel()
    const timeline = await fetchProfileTimeline(profile.id)
    publishedPosts.value = timeline.items
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <section class="space-y-5">
    <header class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Studio</p>
      <h1 class="mt-2 font-headline text-3xl font-semibold text-nx-text">创作入口</h1>
      <p class="mt-3 max-w-2xl text-sm leading-6 text-nx-text-muted">
        先选择继续工作的草稿、已发布内容或新建创作，再进入编辑工作台。
      </p>
    </header>

    <LoadingSkeleton v-if="isLoading" />

    <section v-else class="grid gap-5 xl:grid-cols-[minmax(0,0.95fr)_minmax(0,1.35fr)]">
      <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <div class="flex items-center justify-between gap-4">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Draft</p>
            <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">当前草稿</h2>
          </div>

          <button
            data-test="create-new-draft"
            type="button"
            class="min-h-11 rounded-full bg-nx-primary px-5 text-sm font-semibold text-white"
            @click="openNewDraft"
          >
            新建草稿
          </button>
        </div>

        <article
          v-if="hasWorkspaceDraft"
          class="mt-5 rounded-3xl border border-nx-border bg-nx-surface-muted p-5"
        >
          <p class="text-sm font-semibold text-nx-text">
            {{ composerStore.draft.title || 'Workspace draft' }}
          </p>
          <p class="mt-2 text-sm leading-6 text-nx-text-muted">
            {{ composerStore.draft.body || '继续完成当前草稿，或切换到已发布内容进行修改。' }}
          </p>
          <button
            data-test="open-workspace-draft"
            type="button"
            class="mt-4 min-h-11 rounded-full border border-nx-border px-4 text-sm font-medium text-nx-text"
            @click="openWorkspaceDraft"
          >
            继续编辑
          </button>
        </article>

        <EmptyState
          v-else
          title="还没有本地草稿"
          description="可以直接新建，也可以从右侧已发布内容中选择一篇进入修改流程。"
        />
      </article>

      <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <div class="flex items-center justify-between gap-4">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Published</p>
            <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">已发布内容</h2>
          </div>
        </div>

        <div class="mt-5 space-y-4">
          <TimelineList v-if="publishedPosts.length" :items="publishedPosts" route-mode="edit" />

          <div v-if="publishedPosts.length" class="space-y-3">
            <button
              v-for="item in publishedPosts"
              :key="`edit-${item.id}`"
              :data-test="`open-published-${item.id}`"
              type="button"
              class="w-full rounded-3xl border border-dashed border-nx-border px-4 py-3 text-left text-sm font-medium text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
              @click="openPublishedPost(item.id)"
            >
              编辑：{{ item.summary }}
            </button>
          </div>

          <EmptyState
            v-else
            title="还没有已发布内容"
            description="发布第一篇内容后，这里会提供快速进入修改的入口。"
          />
        </div>
      </article>
    </section>
  </section>
</template>
