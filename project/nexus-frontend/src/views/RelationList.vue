<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFollowers, fetchFollowing, type RelationUserDTO } from '@/api/relation'
import TheNavBar from '@/components/TheNavBar.vue'
import FollowButton from '@/components/FollowButton.vue'

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
    const res = type.value === 'followers'
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
  <div class="page-shell with-top-nav relation-page">
    <TheNavBar />

    <main class="page-content relation-content">
      <div class="tabs surface-card">
        <button
          class="tab"
          :class="{ active: type === 'following' }"
          type="button"
          @click="switchTab('following')"
        >
          关注
        </button>
        <button
          class="tab"
          :class="{ active: type === 'followers' }"
          type="button"
          @click="switchTab('followers')"
        >
          粉丝
        </button>
      </div>

      <section v-if="loading" class="state-card">
        <div class="spinner"></div>
        正在加载列表...
      </section>

      <section v-else-if="error" class="state-card error">
        {{ error }}
      </section>

      <section v-else-if="items.length === 0" class="state-card">
        当前没有数据
      </section>

      <section v-else class="list surface-card">
        <article
          v-for="item in items"
          :key="item.userId"
          class="row"
          @click="router.push(`/user/${item.userId}`)"
        >
          <img :src="item.avatar || 'https://via.placeholder.com/80'" class="avatar" alt="avatar">
          <div class="main">
            <p class="name">{{ item.nickname }}</p>
            <p class="bio text-secondary">{{ item.bio || 'TA 还没有填写简介' }}</p>
          </div>
          <div class="ops" @click.stop>
            <FollowButton :user-id="item.userId" :relation-state="item.relationState" />
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<style scoped>
.relation-content {
  display: grid;
  gap: 12px;
}

.tabs {
  padding: 6px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}

.tab {
  min-height: 40px;
  border-radius: 10px;
  color: var(--text-secondary);
  font-weight: 700;
}

.tab.active {
  background: #fff;
  color: var(--text-primary);
  border: 1px solid var(--border-soft);
}

.list {
  padding: 6px 10px;
}

.row {
  min-height: 70px;
  display: grid;
  grid-template-columns: 48px 1fr auto;
  gap: 12px;
  align-items: center;
  border-bottom: 1px solid #fbe5ea;
  cursor: pointer;
}

.row:last-child {
  border-bottom: none;
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  object-fit: cover;
}

.name {
  font-weight: 700;
}

.bio {
  font-size: 0.85rem;
}

.ops {
  transform: scale(0.95);
  transform-origin: right center;
}

.state-card {
  min-height: 120px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-secondary);
}

.error {
  color: var(--brand-danger);
}
</style>


