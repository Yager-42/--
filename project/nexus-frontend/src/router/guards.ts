import type { Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export function registerAuthGuards(router: Router) {
  router.beforeEach((to) => {
    const authStore = useAuthStore()
    const isLoginRoute = to.name === 'login'

    if (!authStore.isAuthenticated && !isLoginRoute) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (authStore.isAuthenticated && isLoginRoute) {
      return { name: 'timeline' }
    }

    return true
  })
}
