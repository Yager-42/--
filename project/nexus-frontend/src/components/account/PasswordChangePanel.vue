<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenOverlayPanel from '@/components/system/ZenOverlayPanel.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    saving?: boolean
  }>(),
  {
    saving: false
  }
)

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'submit', payload: { oldPassword: string; newPassword: string }): void
}>()

const oldPassword = ref('')
const newPassword = ref('')

const resetForm = () => {
  oldPassword.value = ''
  newPassword.value = ''
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      resetForm()
    }
  }
)

const canSubmit = computed(() => {
  return oldPassword.value.trim().length > 0 && newPassword.value.trim().length > 0 && !props.saving
})

const submit = () => {
  if (!canSubmit.value) {
    return
  }

  emit('submit', {
    oldPassword: oldPassword.value,
    newPassword: newPassword.value
  })
}
</script>

<template>
  <ZenOverlayPanel
    data-password-change-panel
    :open="open"
    title="Change password"
    description="Use your current password once, then set a new one for the next sign-in."
    close-label="Close password panel"
    @close="emit('close')"
  >
    <form class="grid gap-5" @submit.prevent="submit">
      <label class="grid gap-2">
        <span class="text-sm font-semibold text-prototype-ink">Current password</span>
        <input
          v-model="oldPassword"
          name="oldPassword"
          type="password"
          autocomplete="current-password"
          class="min-h-[54px] rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 text-sm text-prototype-ink outline-none transition focus:border-prototype-ink"
          placeholder="Enter current password"
        >
      </label>

      <label class="grid gap-2">
        <span class="text-sm font-semibold text-prototype-ink">New password</span>
        <input
          v-model="newPassword"
          name="newPassword"
          type="password"
          autocomplete="new-password"
          class="min-h-[54px] rounded-[1.25rem] border border-prototype-line bg-prototype-bg px-4 text-sm text-prototype-ink outline-none transition focus:border-prototype-ink"
          placeholder="Enter new password"
        >
      </label>
    </form>

    <template #footer>
      <div class="flex justify-end gap-3">
        <ZenButton variant="secondary" @click="emit('close')">
          Cancel
        </ZenButton>
        <ZenButton
          data-password-change-submit
          variant="primary"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ saving ? 'Saving...' : 'Update password' }}
        </ZenButton>
      </div>
    </template>
  </ZenOverlayPanel>
</template>
