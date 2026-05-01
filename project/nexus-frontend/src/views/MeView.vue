<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import StatusMessage from '@/components/common/StatusMessage.vue'
import TimelineList from '@/components/feed/TimelineList.vue'
import ProfileHeader from '@/components/profile/ProfileHeader.vue'
import RelationSheet from '@/components/profile/RelationSheet.vue'
import { changePassword, logout } from '@/services/api/authApi'
import { deletePost } from '@/services/api/contentApi'
import { fetchProfileTimeline } from '@/services/api/feedApi'
import { fetchMyProfileViewModel, updateMyPrivacy, updateMyProfile } from '@/services/api/profileApi'
import { fetchFollowers, fetchFollowing } from '@/services/api/relationApi'
import { useAuthStore } from '@/stores/auth'
import type { MyProfileViewModel, RelationUserViewModel } from '@/types/profile'
import type { FeedCardViewModel } from '@/types/viewModels'

const router = useRouter()
const authStore = useAuthStore()

const myProfile = ref<MyProfileViewModel>({
  id: 'me',
  nickname: 'My Profile',
  bio: 'Manage profile, privacy, and account settings.',
  followerCountLabel: '0',
  followingCountLabel: '0',
  isFollowing: false,
  needApproval: false
})

const isLoading = ref(false)
const isPasswordSubmitting = ref(false)
const isLogoutSubmitting = ref(false)
const statusMessage = ref('')
const statusTone = ref<'info' | 'error'>('info')
const myPosts = ref<FeedCardViewModel[]>([])
const relationItems = ref<RelationUserViewModel[]>([])
const relationTitle = ref('')
const isRelationSheetOpen = ref(false)
const profileSection = ref<HTMLElement | null>(null)
const nicknameInput = ref<HTMLInputElement | null>(null)

const profileForm = reactive({
  nickname: '',
  avatarUrl: ''
})

const privacyForm = reactive({
  needApproval: false
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: ''
})

function getNumericId(value: string) {
  return Number(value.replace(/\D/g, '') || '0')
}

async function handleProfileUpdate() {
  await updateMyProfile({
    nickname: profileForm.nickname.trim(),
    avatarUrl: profileForm.avatarUrl.trim()
  })

  myProfile.value = {
    ...myProfile.value,
    nickname: profileForm.nickname.trim(),
    avatarUrl: profileForm.avatarUrl.trim()
  }

  statusTone.value = 'info'
  statusMessage.value = '资料已更新'
}

async function handlePrivacyUpdate() {
  await updateMyPrivacy({
    needApproval: privacyForm.needApproval
  })

  myProfile.value = {
    ...myProfile.value,
    needApproval: privacyForm.needApproval
  }

  statusTone.value = 'info'
  statusMessage.value = '隐私设置已更新'
}

async function handlePasswordChange() {
  if (!passwordForm.oldPassword.trim() || !passwordForm.newPassword.trim()) {
    statusTone.value = 'error'
    statusMessage.value = '请输入旧密码和新密码'
    return
  }

  isPasswordSubmitting.value = true
  statusMessage.value = ''

  try {
    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })

    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    statusTone.value = 'info'
    statusMessage.value = '密码已更新'
  } catch (error) {
    statusTone.value = 'error'
    statusMessage.value = error instanceof Error ? error.message : '密码更新失败'
  } finally {
    isPasswordSubmitting.value = false
  }
}

async function handleLogout() {
  isLogoutSubmitting.value = true
  statusMessage.value = ''

  try {
    await logout()
    authStore.logoutLocally()
    await router.push('/login')
  } catch (error) {
    statusTone.value = 'error'
    statusMessage.value = error instanceof Error ? error.message : '退出失败'
  } finally {
    isLogoutSubmitting.value = false
  }
}

async function handleEditProfile() {
  await nextTick()
  profileSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  nicknameInput.value?.focus()
}

async function handleEditPost(item: FeedCardViewModel) {
  await router.push(`/compose/editor?postId=${item.id}`)
}

async function handleDeletePost(item: FeedCardViewModel) {
  if (!window.confirm('确认删除这条已发布内容吗？')) {
    return
  }

  await deletePost(item.id, {
    userId: Number(myProfile.value.id),
    postId: getNumericId(item.id)
  })

  myPosts.value = myPosts.value.filter((post) => post.id !== item.id)
  statusTone.value = 'info'
  statusMessage.value = '内容已删除'
}

async function openFollowers() {
  const response = await fetchFollowers(myProfile.value.id)
  relationTitle.value = '粉丝列表'
  relationItems.value = response.items
  isRelationSheetOpen.value = true
}

async function openFollowing() {
  const response = await fetchFollowing(myProfile.value.id)
  relationTitle.value = '关注列表'
  relationItems.value = response.items
  isRelationSheetOpen.value = true
}

onMounted(async () => {
  isLoading.value = true

  try {
    myProfile.value = await fetchMyProfileViewModel()
    const timeline = await fetchProfileTimeline(myProfile.value.id)
    myPosts.value = timeline.items
    profileForm.nickname = myProfile.value.nickname
    profileForm.avatarUrl = myProfile.value.avatarUrl ?? ''
    privacyForm.needApproval = myProfile.value.needApproval
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <section class="space-y-5">
    <LoadingSkeleton v-if="isLoading" />
    <ProfileHeader
      :profile="myProfile"
      me
      @edit-profile="handleEditProfile"
      @show-followers="openFollowers"
      @show-following="openFollowing"
    />

    <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
      <div class="flex items-center justify-between gap-4">
        <div>
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Published</p>
          <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">我的已发布内容</h2>
        </div>
      </div>

      <div class="mt-5">
        <TimelineList
          v-if="myPosts.length"
          :items="myPosts"
          show-owner-actions
          @edit-item="handleEditPost"
          @delete-item="handleDeletePost"
        />
        <EmptyState
          v-else
          title="还没有发布内容"
          description="发布后，这里会以时间线方式展示你的内容。"
        />
      </div>
    </section>

    <section class="grid gap-4 lg:grid-cols-2">
      <article
        ref="profileSection"
        class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]"
      >
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Profile</p>
        <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">编辑公开资料</h2>

        <form data-test="profile-form" class="mt-5 space-y-4" @submit.prevent="handleProfileUpdate">
          <div class="space-y-2">
            <label class="text-sm font-medium text-nx-text" for="profileNickname">昵称</label>
            <input
              id="profileNickname"
              ref="nicknameInput"
              v-model="profileForm.nickname"
              data-test="profile-nickname-input"
              class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div class="space-y-2">
            <label class="text-sm font-medium text-nx-text" for="profileAvatar">头像地址</label>
            <input
              id="profileAvatar"
              v-model="profileForm.avatarUrl"
              data-test="profile-avatar-input"
              class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <button
            type="submit"
            class="inline-flex min-h-11 items-center justify-center rounded-full bg-nx-primary px-5 text-sm font-semibold text-white"
          >
            更新资料
          </button>
        </form>
      </article>

      <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Privacy</p>
        <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">隐私与互动门槛</h2>

        <form data-test="privacy-form" class="mt-5 space-y-4" @submit.prevent="handlePrivacyUpdate">
          <label class="flex min-h-11 items-center justify-between gap-4 rounded-3xl border border-nx-border bg-white px-4 py-3">
            <span class="text-sm font-medium text-nx-text">新的关注需要审核</span>
            <input
              v-model="privacyForm.needApproval"
              data-test="privacy-approval-toggle"
              type="checkbox"
              class="h-5 w-5 accent-[var(--nx-primary)]"
            />
          </label>

          <p class="text-sm leading-6 text-nx-text-muted">
            {{ privacyForm.needApproval ? '当前需要审核新的关注请求。' : '当前允许直接建立关注关系。' }}
          </p>

          <button
            type="submit"
            class="inline-flex min-h-11 items-center justify-center rounded-full border border-nx-border px-5 text-sm font-semibold text-nx-text"
          >
            更新隐私设置
          </button>
        </form>
      </article>
    </section>

    <section class="grid gap-4 xl:grid-cols-[minmax(0,1.3fr)_minmax(18rem,1fr)]">
      <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Security</p>
        <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">修改密码</h2>
        <p class="mt-3 text-sm leading-6 text-nx-text-muted">先补全账户安全，再继续完善个人设置。</p>

        <form data-test="password-form" class="mt-5 space-y-4" @submit.prevent="handlePasswordChange">
          <div class="space-y-2">
            <label class="text-sm font-medium text-nx-text" for="oldPassword">旧密码</label>
            <input
              id="oldPassword"
              v-model="passwordForm.oldPassword"
              data-test="old-password-input"
              type="password"
              class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div class="space-y-2">
            <label class="text-sm font-medium text-nx-text" for="newPassword">新密码</label>
            <input
              id="newPassword"
              v-model="passwordForm.newPassword"
              data-test="new-password-input"
              type="password"
              class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <StatusMessage v-if="statusMessage" :message="statusMessage" :tone="statusTone" />

          <button
            type="submit"
            class="inline-flex min-h-11 items-center justify-center rounded-full bg-nx-primary px-5 text-sm font-semibold text-white"
          >
            {{ isPasswordSubmitting ? '提交中...' : '更新密码' }}
          </button>
        </form>
      </article>

      <article class="rounded-[var(--nx-radius-card)] border border-red-200 bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-red-500">Session</p>
        <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">退出当前账户</h2>
        <p class="mt-3 text-sm leading-6 text-nx-text-muted">远端退出成功后，立即清除本地凭证并返回登录页。</p>

        <button
          data-test="logout-button"
          type="button"
          class="mt-5 inline-flex min-h-11 items-center justify-center rounded-full border border-red-200 px-5 text-sm font-semibold text-red-600"
          @click="handleLogout"
        >
          {{ isLogoutSubmitting ? '退出中...' : '退出登录' }}
        </button>
      </article>
    </section>

    <RelationSheet
      v-if="isRelationSheetOpen"
      :title="relationTitle"
      :items="relationItems"
      @close="isRelationSheetOpen = false"
    />
  </section>
</template>
