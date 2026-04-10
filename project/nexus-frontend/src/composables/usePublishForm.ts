import { computed, ref } from 'vue'

export const usePublishForm = () => {
  const title = ref('')
  const content = ref('')
  const previews = ref<string[]>([])
  const mediaIds = ref<string[]>([])
  const uploadProgress = ref(0)
  const uploadError = ref('')

  const canPublish = computed(() => content.value.trim().length > 0)

  const removeMediaAt = (index: number) => {
    previews.value.splice(index, 1)
    mediaIds.value.splice(index, 1)
  }

  return {
    title,
    content,
    previews,
    mediaIds,
    uploadProgress,
    uploadError,
    canPublish,
    removeMediaAt
  }
}

