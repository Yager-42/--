<script setup lang="ts">
import type { ContentHistoryVersionDTO } from '@/api/content'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenOverlayPanel from '@/components/system/ZenOverlayPanel.vue'

defineProps<{
  open: boolean
  loading?: boolean
  restoring?: boolean
  items: ContentHistoryVersionDTO[]
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'restore', versionId: string): void
}>()

const formatTime = (timestamp: number) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  })
</script>

<template>
  <ZenOverlayPanel
    :open="open"
    width-class="max-w-[34rem]"
    title="Version history"
    description="Review prior published revisions and restore one into the current editor."
    close-label="Close history drawer"
    @close="emit('close')"
  >
    <div v-if="loading" class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg px-5 py-8 text-sm text-prototype-muted">
      Loading version history...
    </div>

    <div v-else-if="items.length === 0" class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg px-5 py-8 text-sm text-prototype-muted">
      No published versions yet.
    </div>

    <div v-else class="grid gap-4">
      <article
        v-for="item in items"
        :key="item.versionId"
        class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg p-5"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="space-y-2">
            <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-prototype-muted">
              {{ formatTime(item.time) }}
            </p>
            <h3 class="text-lg font-semibold text-prototype-ink">
              {{ item.title || 'Untitled version' }}
            </h3>
            <p class="line-clamp-3 text-sm leading-7 text-prototype-muted">
              {{ item.content }}
            </p>
          </div>

          <ZenButton
            :data-history-restore="item.versionId"
            variant="secondary"
            :disabled="restoring"
            @click="emit('restore', item.versionId)"
          >
            Restore
          </ZenButton>
        </div>
      </article>
    </div>
  </ZenOverlayPanel>
</template>
