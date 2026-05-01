<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import TimelineList from '@/components/feed/TimelineList.vue'
import ProfileHeader from '@/components/profile/ProfileHeader.vue'
import RelationSheet from '@/components/profile/RelationSheet.vue'
import { fetchProfileTimeline } from '@/services/api/feedApi'
import { fetchProfileHeader } from '@/services/api/profileApi'
import { fetchFollowers, fetchFollowing, followUser, unfollowUser } from '@/services/api/relationApi'
import type { ProfileHeaderViewModel, RelationUserViewModel } from '@/types/profile'
import type { FeedCardViewModel } from '@/types/viewModels'

const route = useRoute()

const props = defineProps<{
  profile?: ProfileHeaderViewModel
  posts?: FeedCardViewModel[]
}>()

const remoteProfile = ref<ProfileHeaderViewModel | null>(null)
const remotePosts = ref<FeedCardViewModel[]>([])
const isLoading = ref(false)
const relationItems = ref<RelationUserViewModel[]>([])
const relationTitle = ref('')
const isRelationSheetOpen = ref(false)

const profileModel = computed<ProfileHeaderViewModel>(() => {
  return (
    props.profile ??
    remoteProfile.value ?? {
      id: '0',
      nickname: '未知用户',
      bio: '资料暂不可用',
      followerCountLabel: '0',
      followingCountLabel: '0',
      isFollowing: false
    }
  )
})

const posts = computed(() => props.posts ?? remotePosts.value)

async function toggleFollow() {
  const current = profileModel.value

  if (current.isFollowing) {
    await unfollowUser({
      sourceId: 7,
      targetId: Number(current.id)
    })
  } else {
    await followUser({
      sourceId: 7,
      targetId: Number(current.id)
    })
  }

  remoteProfile.value = {
    ...current,
    isFollowing: !current.isFollowing
  }
}

async function openFollowers() {
  const response = await fetchFollowers(Number(profileModel.value.id))
  relationTitle.value = '粉丝列表'
  relationItems.value = response.items
  isRelationSheetOpen.value = true
}

async function openFollowing() {
  const response = await fetchFollowing(Number(profileModel.value.id))
  relationTitle.value = '关注列表'
  relationItems.value = response.items
  isRelationSheetOpen.value = true
}

onMounted(async () => {
  if (props.profile || !route.params.id) {
    return
  }

  isLoading.value = true

  try {
    const targetId = String(route.params.id)
    const [profile, feed] = await Promise.all([
      fetchProfileHeader(targetId),
      fetchProfileTimeline(targetId)
    ])

    remoteProfile.value = profile
    remotePosts.value = feed.items
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <section class="space-y-5">
    <LoadingSkeleton v-if="isLoading" />
    <ProfileHeader
      :profile="profileModel"
      @toggle-follow="toggleFollow"
      @show-followers="openFollowers"
      @show-following="openFollowing"
    />

    <TimelineList v-if="posts.length" :items="posts" />

    <EmptyState
      v-else
      title="这个主页还没有内容"
      description="当该用户发布内容后，这里会延续时间线的阅读体验。"
    />

    <RelationSheet
      v-if="isRelationSheetOpen"
      :title="relationTitle"
      :items="relationItems"
      @close="isRelationSheetOpen = false"
    />
  </section>
</template>
