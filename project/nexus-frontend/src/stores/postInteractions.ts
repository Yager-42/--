import { ref } from 'vue'
import { defineStore } from 'pinia'

type PostInteractionState = {
  liked: boolean
  likeCountLabel: string
}

export const usePostInteractionsStore = defineStore('postInteractions', () => {
  const byPostId = ref<Record<string, PostInteractionState>>({})

  function getInteraction(postId: string) {
    return byPostId.value[postId]
  }

  function updateInteraction(postId: string, interaction: PostInteractionState) {
    byPostId.value = {
      ...byPostId.value,
      [postId]: interaction
    }
  }

  function primeInteraction(postId: string, interaction: PostInteractionState) {
    if (byPostId.value[postId]) {
      return
    }

    updateInteraction(postId, interaction)
  }

  function clearInteractions() {
    byPostId.value = {}
  }

  return {
    byPostId,
    getInteraction,
    primeInteraction,
    updateInteraction,
    clearInteractions
  }
})
