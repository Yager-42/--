import { describe, expect, it } from 'vitest'
import { createAppRouter } from '@/router'
import LoginView from '@/views/LoginView.vue'
import AppShell from '@/layouts/AppShell.vue'

describe('createAppRouter', () => {
  it('registers the editorial social route map', () => {
    const router = createAppRouter()
    const routeNames = router.getRoutes().map((route) => route.name)

    expect(routeNames).toEqual(
      expect.arrayContaining([
        'login',
        'timeline',
        'search',
        'profile',
        'me',
        'notifications',
        'post-detail',
        'compose',
        'compose-editor'
      ])
    )
  })

  it('uses LoginView for the login route', () => {
    const router = createAppRouter()
    const loginRoute = router.getRoutes().find((route) => route.name === 'login')

    expect(loginRoute?.components?.default).toBe(LoginView)
  })

  it('uses AppShell for authenticated routes', () => {
    const router = createAppRouter()
    const shellRoute = router.getRoutes().find((route) => route.name === 'app-shell')

    expect(shellRoute?.components?.default).toBe(AppShell)
    expect(shellRoute?.children.map((route) => route.name)).toEqual(
      expect.arrayContaining(['timeline', 'search', 'profile', 'me', 'notifications', 'post-detail', 'compose', 'compose-editor'])
    )
  })
})
