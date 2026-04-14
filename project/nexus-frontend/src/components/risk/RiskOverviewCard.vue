<script setup lang="ts">
import ZenButton from '@/components/primitives/ZenButton.vue'

defineProps<{
  status: 'NORMAL' | 'LIMITED' | 'BANNED'
  capabilities: string[]
  appealReady: boolean
}>()

defineEmits<{
  (event: 'appeal'): void
}>()
</script>

<template>
  <section class="grid gap-5">
    <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
      Risk Overview
    </p>
    <h2 class="font-headline text-4xl tracking-[-0.04em] text-prototype-ink md:text-5xl">
      当前账号状态：{{ status }}
    </h2>
    <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
      当前状态与能力限制来自后端风险接口；只有带齐处罚参数时才允许进入可提交的申诉流程。
    </p>

    <div class="flex flex-wrap gap-3">
      <span
        v-for="item in capabilities"
        :key="item"
        class="inline-flex min-h-[2.5rem] items-center rounded-full border border-prototype-line bg-prototype-bg px-4 text-sm text-prototype-muted"
      >
        {{ item }}
      </span>
      <span
        v-if="capabilities.length === 0"
        class="inline-flex min-h-[2.5rem] items-center rounded-full border border-prototype-line bg-prototype-bg px-4 text-sm text-prototype-muted"
      >
        暂无限制说明
      </span>
    </div>

    <ZenButton
      variant="primary"
      class="justify-self-start"
      :disabled="!appealReady"
      @click="$emit('appeal')"
    >
      {{ appealReady ? '进入申诉' : '当前无可申诉案件' }}
    </ZenButton>
  </section>
</template>

