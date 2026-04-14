<script setup lang="ts">
import { computed } from 'vue'
import type { PublishAttemptResponseDTO } from '@/api/content'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

const props = defineProps<{
  draftId?: string | null
  postId?: string | null
  attemptId?: string | null
  attempt?: PublishAttemptResponseDTO | null
}>()

const statusLabel = computed(() => {
  const status = props.attempt?.attemptStatus ?? null

  if (status === 1) return 'Publishing'
  if (status === 2) return 'Published'
  if (status === 3) return 'Retry needed'
  return props.attemptId ? 'Queued' : 'Draft saved'
})

const metaLines = computed(() => {
  const lines: string[] = []

  if (props.draftId) {
    lines.push(`Draft ID ${props.draftId}`)
  }

  if (props.postId) {
    lines.push(`Post ID ${props.postId}`)
  }

  if (props.attemptId) {
    lines.push(`Attempt ID ${props.attemptId}`)
  }

  return lines
})
</script>

<template>
  <section
    class="flex flex-wrap items-start justify-between gap-4 rounded-[1.75rem] border border-prototype-line bg-[rgba(255,255,255,0.84)] px-5 py-4 shadow-[0_18px_42px_rgba(27,31,31,0.08)] backdrop-blur-sm"
  >
    <div class="space-y-2">
      <div class="flex items-center gap-2">
        <ZenIcon
          name="progress_activity"
          :size="18"
          class="text-prototype-accent"
          :class="attemptId ? 'animate-spin' : ''"
        />
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-prototype-muted">
          {{ statusLabel }}
        </p>
      </div>

      <div class="flex flex-wrap gap-2">
        <span
          v-for="line in metaLines"
          :key="line"
          class="rounded-full border border-prototype-line bg-prototype-surface px-3 py-1 text-[11px] font-semibold tracking-[0.08em] text-prototype-ink"
        >
          {{ line }}
        </span>
      </div>
    </div>

    <p v-if="attempt?.errorMessage" class="max-w-[22rem] text-sm leading-6 text-error">
      {{ attempt.errorMessage }}
    </p>
  </section>
</template>
