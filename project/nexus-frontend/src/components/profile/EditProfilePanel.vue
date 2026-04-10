<script setup lang="ts">
defineProps<{
  nickname: string
  avatarUrl: string
  loading?: boolean
}>()

defineEmits<{
  (event: 'update:nickname', value: string): void
  (event: 'update:avatarUrl', value: string): void
  (event: 'cancel'): void
  (event: 'save'): void
}>()
</script>

<template>
  <section class="edit-profile-panel">
    <div class="edit-profile-panel__head">
      <div>
        <p class="edit-profile-panel__eyebrow">Edit Mode</p>
        <h2 class="text-headline">更新昵称与头像链接</h2>
      </div>
    </div>

    <label class="edit-profile-panel__field">
      <span>昵称</span>
      <input
        :value="nickname"
        type="text"
        placeholder="输入新的昵称"
        @input="$emit('update:nickname', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <label class="edit-profile-panel__field">
      <span>头像链接</span>
      <input
        :value="avatarUrl"
        type="url"
        placeholder="输入头像图片地址"
        @input="$emit('update:avatarUrl', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <div class="edit-profile-panel__actions">
      <button type="button" class="secondary-btn" @click="$emit('cancel')">取消</button>
      <button type="button" class="primary-btn" :disabled="loading" @click="$emit('save')">
        {{ loading ? '保存中...' : '保存资料' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.edit-profile-panel {
  display: grid;
  gap: 1rem;
  padding: 1rem;
  border-radius: var(--radius-panel);
  border: 1px solid var(--border-ghost);
  background: rgba(255, 251, 245, 0.78);
}

.edit-profile-panel__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.edit-profile-panel__field {
  display: grid;
  gap: 0.45rem;
}

.edit-profile-panel__field span {
  color: var(--text-secondary);
}

.edit-profile-panel__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}
</style>

