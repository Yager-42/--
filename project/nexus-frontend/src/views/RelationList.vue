<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFollowers, fetchFollowing, type RelationUserDTO } from '@/api/relation'
import FollowButton from '@/components/FollowButton.vue'
import StatePanel from '@/components/system/StatePanel.vue'
import TheNavBar from '@/components/TheNavBar.vue'

const route = useRoute()
const router = useRouter()

const type = ref(String(route.params.type || 'following'))
const userId = ref(String(route.params.userId || ''))
const items = ref<RelationUserDTO[]>([])
const loading = ref(false)
const error = ref('')

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
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main">
      <section class="grid gap-6">
        <div class="relation-header">
          <p class="relation-header__eyebrow">Connections</p>
          <h1 class="text-large-title">{{ type === 'following' ? 'Following' : 'Followers' }}</h1>
        </div>

        <div class="relation-tabs">
          <button
            class="relation-tabs__item"
            :class="{ 'relation-tabs__item--active': type === 'following' }"
            type="button"
            @click="switchTab('following')"
          >
            关注
          </button>
          <button
            class="relation-tabs__item"
            :class="{ 'relation-tabs__item--active': type === 'followers' }"
            type="button"
            @click="switchTab('followers')"
          >
            粉丝
          </button>
        </div>

        <StatePanel
          v-if="loading"
          variant="loading"
          title="正在整理连接关系"
          body="人物列表正在按顺序准备好。"
        />

        <StatePanel
          v-else-if="error"
          variant="request-failure"
          :body="error"
          primary-label="重试"
          @primary="loadData"
        />

        <StatePanel
          v-else-if="items.length === 0"
          variant="empty"
          title="这里还没有可展示的人"
          body="等你开始关注更多创作者，这里会自然形成新的联系网络。"
        />

        <section v-else class="relation-list">
          <article
            v-for="item in items"
            :key="item.userId"
            class="relation-list__row"
            @click="router.push(`/user/${item.userId}`)"
          >
            <img :src="item.avatar || 'https://via.placeholder.com/80'" class="relation-list__avatar" alt="avatar">
            <div class="relation-list__main">
              <p class="relation-list__name">{{ item.nickname }}</p>
              <p class="relation-list__bio">{{ item.bio || 'TA 还没有填写简介。' }}</p>
            </div>
            <div class="relation-list__ops" @click.stop>
              <FollowButton :user-id="item.userId" :relation-state="item.relationState" />
            </div>
          </article>
        </section>
      </section>
    </main>
  </div>
</template>

<style scoped>
.relation-header {
  display: grid;
  gap: 0.7rem;
}

.relation-header__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.relation-tabs {
  width: fit-content;
  display: grid;
  grid-template-columns: repeat(2, minmax(6rem, 1fr));
  gap: 0.4rem;
  padding: 0.35rem;
  border-radius: 999px;
  background: rgba(255, 251, 244, 0.84);
  border: 1px solid var(--border-ghost);
}

.relation-tabs__item {
  min-height: 2.5rem;
  border-radius: 999px;
  color: var(--text-secondary);
  font-weight: 600;
}

.relation-tabs__item--active {
  background: var(--brand-primary);
  color: var(--text-on-dark);
}

.relation-list {
  display: grid;
  gap: 0.8rem;
}

.relation-list__row {
  min-height: 5.5rem;
  display: grid;
  grid-template-columns: 3.25rem minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: center;
  padding: 1rem;
  border-radius: var(--radius-panel);
  border: 1px solid var(--border-ghost);
  background: rgba(255, 251, 245, 0.76);
  cursor: pointer;
}

.relation-list__avatar {
  width: 3.25rem;
  height: 3.25rem;
  border-radius: 50%;
  object-fit: cover;
}

.relation-list__main {
  min-width: 0;
  display: grid;
  gap: 0.3rem;
}

.relation-list__name {
  font-family: var(--font-display);
  font-size: 1.05rem;
  font-weight: 700;
}

.relation-list__bio {
  color: var(--text-secondary);
  line-height: 1.6;
}
</style>
