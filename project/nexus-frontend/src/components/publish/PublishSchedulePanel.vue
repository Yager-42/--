<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ScheduleAuditResponseDTO } from '@/api/content'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenOverlayPanel from '@/components/system/ZenOverlayPanel.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    saving?: boolean
    task?: ScheduleAuditResponseDTO | null
  }>(),
  {
    saving: false,
    task: null
  }
)

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'submit', payload: { publishTime: number }): void
  (event: 'cancel'): void
}>()

const publishAt = ref('2026-04-12T18:00')

watch(
  () => props.open,
  (open) => {
    if (!open) return

    if (props.task?.scheduleTime) {
      publishAt.value = new Date(props.task.scheduleTime).toISOString().slice(0, 16)
    }
  },
  { immediate: true }
)

const summary = computed(() => {
  if (!props.task?.scheduleTime) {
    return 'Choose a desktop-only future publish slot.'
  }

  return `Scheduled publication ${new Date(props.task.scheduleTime).toLocaleString('en-US')}`
})

const canSubmit = computed(() => publishAt.value.trim().length > 0 && !props.saving)

const submit = () => {
  if (!canSubmit.value) return

  emit('submit', {
    publishTime: new Date(publishAt.value).getTime()
  })
}
</script>

<template>
  <ZenOverlayPanel
    :open="open"
    width-class="max-w-[32rem]"
    title="Scheduled publication"
    :description="summary"
    close-label="Close schedule panel"
    @close="emit('close')"
  >
    <div class="grid gap-5">
      <label class="grid gap-2">
        <span class="text-sm font-semibold text-prototype-ink">Publish time</span>
        <input
          v-model="publishAt"
          type="datetime-local"
          class="min-h-[54px] rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 text-sm text-prototype-ink outline-none transition focus:border-prototype-ink"
        >
      </label>

      <div v-if="task" class="rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 py-3 text-sm text-prototype-muted">
        Task ID {{ task.taskId }}
      </div>
    </div>

    <template #footer>
      <div class="flex justify-between gap-3">
        <ZenButton
          data-schedule-cancel
          variant="text"
          :disabled="!task || saving"
          @click="emit('cancel')"
        >
          Cancel schedule
        </ZenButton>

        <div class="flex gap-3">
          <ZenButton variant="secondary" @click="emit('close')">
            Close
          </ZenButton>
          <ZenButton
            data-schedule-submit
            variant="primary"
            :disabled="!canSubmit"
            @click="submit"
          >
            {{ saving ? 'Saving...' : task ? 'Reschedule' : 'Schedule' }}
          </ZenButton>
        </div>
      </div>
    </template>
  </ZenOverlayPanel>
</template>
