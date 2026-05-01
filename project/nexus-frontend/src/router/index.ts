import { createMemoryHistory, createRouter, createWebHistory } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import LoginView from '@/views/LoginView.vue'
import MeView from '@/views/MeView.vue'
import NotificationsView from '@/views/NotificationsView.vue'
import PostDetailView from '@/views/PostDetailView.vue'
import ProfileView from '@/views/ProfileView.vue'
import SearchView from '@/views/SearchView.vue'
import TimelineView from '@/views/TimelineView.vue'
import ComposerView from '@/views/ComposerView.vue'
import ComposeHubView from '@/views/ComposeHubView.vue'
import { registerAuthGuards } from './guards'

export function createAppRouter() {
  const history = import.meta.env.MODE === 'test' ? createMemoryHistory() : createWebHistory()

  const router = createRouter({
    history,
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      {
        path: '/',
        name: 'app-shell',
        component: AppShell,
        children: [
          { path: '', name: 'timeline', component: TimelineView },
          { path: 'search', name: 'search', component: SearchView },
          { path: 'profile/:id', name: 'profile', component: ProfileView },
          { path: 'me', name: 'me', component: MeView },
          { path: 'notifications', name: 'notifications', component: NotificationsView },
          { path: 'post/:id', name: 'post-detail', component: PostDetailView },
          { path: 'compose', name: 'compose', component: ComposeHubView },
          { path: 'compose/editor', name: 'compose-editor', component: ComposerView }
        ]
      }
    ]
  })

  registerAuthGuards(router)

  return router
}
