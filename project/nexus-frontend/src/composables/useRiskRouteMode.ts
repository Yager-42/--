import { computed } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

export const useRiskRouteMode = (route: RouteLocationNormalizedLoaded) => {
  const decisionId = computed(() =>
    typeof route.query.decisionId === 'string' ? route.query.decisionId : ''
  )
  const punishId = computed(() =>
    typeof route.query.punishId === 'string' ? route.query.punishId : ''
  )

  const appealReady = computed(() => Boolean(decisionId.value && punishId.value))
  const appealUnavailable = computed(() => !appealReady.value)

  return {
    decisionId,
    punishId,
    appealReady,
    appealUnavailable
  }
}

