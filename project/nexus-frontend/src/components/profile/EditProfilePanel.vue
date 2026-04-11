<script setup lang="ts">
import ZenButton from '@/components/primitives/ZenButton.vue'

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
  <section class="grid gap-6 rounded-[1.75rem] border border-prototype-line bg-prototype-bg p-6">
    <div>
      <div class="space-y-2">
        <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
          Edit Mode
        </p>
        <h2 class="font-headline text-3xl tracking-[-0.04em] text-prototype-ink">更新昵称与头像链接</h2>
      </div>
    </div>

    <label class="grid gap-2">
      <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">昵称</span>
      <input
        :value="nickname"
        type="text"
        placeholder="输入新的昵称"
        class="rounded-full border border-prototype-line bg-prototype-surface px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
        @input="$emit('update:nickname', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <label class="grid gap-2">
      <span class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">头像链接</span>
      <input
        :value="avatarUrl"
        type="url"
        placeholder="输入头像图片地址"
        class="rounded-full border border-prototype-line bg-prototype-surface px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
        @input="$emit('update:avatarUrl', ($event.target as HTMLInputElement).value)"
      >
    </label>

    <div class="flex justify-end gap-3">
      <ZenButton variant="secondary" @click="$emit('cancel')">取消</ZenButton>
      <ZenButton variant="primary" :disabled="loading" @click="$emit('save')">
        {{ loading ? '保存中...' : '保存资料' }}
      </ZenButton>
    </div>
  </section>
</template>

