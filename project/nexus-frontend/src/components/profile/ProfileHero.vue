<script setup lang="ts">
import type { ProfilePageViewModel } from '@/api/user'

defineProps<{
  profile: ProfilePageViewModel
  isMyProfile: boolean
}>()

defineEmits<{
  (event: 'edit'): void
}>()
</script>

<template>
  <section class="profile-hero">
    <div class="profile-hero__identity">
      <img :src="profile.avatar || 'https://via.placeholder.com/160'" alt="avatar" class="profile-hero__avatar">
      <div class="profile-hero__copy">
        <p class="profile-hero__eyebrow">Creator Profile</p>
        <h1 class="text-large-title">{{ profile.nickname }}</h1>
        <p class="text-body text-secondary">
          {{ profile.bio || '这个用户还没有填写个人简介。' }}
        </p>
      </div>
    </div>

    <div class="profile-hero__stats">
      <div class="profile-hero__stat">
        <strong>{{ profile.stats.likeCount }}</strong>
        <span>获赞</span>
      </div>
      <div class="profile-hero__stat">
        <strong>{{ profile.stats.followCount }}</strong>
        <span>关注</span>
      </div>
      <div class="profile-hero__stat">
        <strong>{{ profile.stats.followerCount }}</strong>
        <span>粉丝</span>
      </div>
    </div>

    <button v-if="isMyProfile" type="button" class="secondary-btn profile-hero__edit" @click="$emit('edit')">
      编辑资料
    </button>
  </section>
</template>

<style scoped>
.profile-hero {
  display: grid;
  gap: 1.2rem;
}

.profile-hero__identity {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 1.2rem;
  align-items: center;
}

.profile-hero__avatar {
  width: 7rem;
  height: 7rem;
  border-radius: 50%;
  object-fit: cover;
}

.profile-hero__copy {
  display: grid;
  gap: 0.7rem;
}

.profile-hero__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.profile-hero__stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.8rem;
}

.profile-hero__stat {
  display: grid;
  justify-items: center;
  gap: 0.25rem;
  padding: 0.95rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-ghost);
  background: rgba(255, 251, 245, 0.72);
}

.profile-hero__stat strong {
  font-family: var(--font-display);
  font-size: 1.2rem;
}

.profile-hero__stat span {
  color: var(--text-secondary);
}

.profile-hero__edit {
  justify-self: start;
}

@media (max-width: 720px) {
  .profile-hero__identity {
    grid-template-columns: 1fr;
    text-align: center;
    justify-items: center;
  }
}
</style>

