<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFollowers, fetchFollowing, type RelationUserDTO } from '@/api/relation'
import FollowButton from '@/components/FollowButton.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import StatePanel from '@/components/system/StatePanel.vue'

const route = useRoute()
const router = useRouter()

const type = ref(String(route.params.type || 'following'))
const userId = ref(String(route.params.userId || ''))
const items = ref<RelationUserDTO[]>([])
const loading = ref(false)
const error = ref('')
const searchQuery = ref('')

const filteredItems = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) {
    return items.value
  }

  return items.value.filter((item) => {
    return (
      item.nickname.toLowerCase().includes(keyword) ||
      item.bio.toLowerCase().includes(keyword)
    )
  })
})

const loadData = async () => {
  if (!userId.value) {
    error.value = '缺少用户参数'
    return
  }

  loading.value = true
  error.value = ''
  try {
    const res =
      type.value === 'followers'
        ? await fetchFollowers({ userId: userId.value })
        : await fetchFollowing({ userId: userId.value })
    items.value = res.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : '关系列表加载失败'
    items.value = []
  } finally {
    loading.value = false
  }
}

const switchTab = (nextType: 'followers' | 'following') => {
  if (type.value === nextType) return
  void router.replace(`/relation/${nextType}/${userId.value}`)
}

watch(
  () => [route.params.type, route.params.userId],
  ([nextType, nextUser]) => {
    type.value = String(nextType || 'following')
    userId.value = String(nextUser || '')
    void loadData()
  },
  { immediate: true }
)
</script>

<template>
  <PrototypeShell>
    <article data-prototype-relation class="space-y-16 pb-20">
      <PrototypeContainer class="space-y-8 pt-12">
        <div class="space-y-3 text-center">
          <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
            Connections
          </p>
          <h1 class="font-headline text-5xl tracking-[-0.05em] text-prototype-ink md:text-6xl">
            {{ type === 'following' ? 'Following' : 'Followers' }}
          </h1>
          <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
            关系链只展示真实关注与粉丝数据，不添加额外推荐层。
          </p>
        </div>

        <div class="space-y-6">
          <div class="flex justify-center">
            <div class="inline-flex w-fit gap-1 rounded-full bg-prototype-surface p-1">
              <button
                class="min-h-[3rem] rounded-full px-8 text-sm font-semibold tracking-[0.08em] transition"
                :class="type === 'followers'
                  ? 'bg-prototype-ink text-prototype-surface'
                  : 'text-prototype-muted hover:text-prototype-ink'"
                type="button"
                @click="switchTab('followers')"
              >
                Followers
              </button>
              <button
                class="min-h-[3rem] rounded-full px-8 text-sm font-semibold tracking-[0.08em] transition"
                :class="type === 'following'
                  ? 'bg-prototype-ink text-prototype-surface'
                  : 'text-prototype-muted hover:text-prototype-ink'"
                type="button"
                @click="switchTab('following')"
              >
                Following
              </button>
            </div>
          </div>

          <div class="mx-auto max-w-md">
            <input
              v-model="searchQuery"
              type="search"
              class="w-full rounded-[1rem] border border-prototype-line bg-prototype-surface px-5 py-4 text-sm text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
              placeholder="Search by name or status..."
            >
          </div>
        </div>
      </PrototypeContainer>

      <PrototypeContainer v-if="loading" width="content">
        <StatePanel
          variant="loading"
          title="正在整理连接关系"
          body="人物列表正在按顺序准备好。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="error" width="content">
        <StatePanel
          variant="request-failure"
          :body="error"
          primary-label="重试"
          @primary="loadData"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else-if="items.length === 0" width="content">
        <StatePanel
          variant="empty"
          title="这里还没有可展示的人"
          body="等你开始关注更多创作者，这里会自然形成新的联系网络。"
        />
      </PrototypeContainer>

      <PrototypeContainer v-else width="content" class="space-y-10">
        <section class="space-y-4">
          <article
            v-for="(item, index) in filteredItems"
            :key="item.userId"
            class="grid cursor-pointer gap-4 rounded-[1.25rem] border border-prototype-line bg-prototype-surface p-6 transition hover:-translate-y-0.5 md:grid-cols-[4rem,minmax(0,1fr),auto] md:items-center"
            :class="index % 2 === 0 ? 'mr-4 ml-0' : 'ml-4 mr-0'"
            @click="router.push(`/user/${item.userId}`)"
          >
            <img
              :src="item.avatar || 'https://via.placeholder.com/80'"
              class="h-16 w-16 rounded-full object-cover"
              alt="avatar"
            >
            <div class="min-w-0 space-y-2">
              <p class="font-headline text-2xl tracking-[-0.03em] text-prototype-ink">
                {{ item.nickname }}
              </p>
              <p class="text-sm leading-7 text-prototype-muted">
                {{ item.bio || 'TA 还没有填写简介。' }}
              </p>
            </div>
            <div @click.stop>
              <FollowButton :user-id="item.userId" :relation-state="item.relationState" />
            </div>
          </article>
        </section>

        <section class="rounded-[2rem] bg-prototype-bg/60 px-6 py-12 text-center">
          <span class="material-symbols-outlined mb-4 text-4xl text-prototype-muted">group_add</span>
          <p class="text-lg font-semibold text-prototype-ink">Discover more creators</p>
          <p class="mt-1 text-sm text-prototype-muted">
            Grow your gallery by following like-minded visionaries.
          </p>
          <button
            type="button"
            class="mt-6 text-sm font-semibold text-prototype-accent transition hover:text-prototype-ink"
            @click="router.push('/search')"
          >
            Browse Discover Page
          </button>
        </section>
      </PrototypeContainer>
    </article>
  </PrototypeShell>
</template>
