import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createAppRouter } from './router'
import { registerSessionExpiredHandler } from './services/http/client'
import { useAuthStore } from './stores/auth'
import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()
const router = createAppRouter()

app.use(pinia)
app.use(router)

registerSessionExpiredHandler(async (redirectPath) => {
  useAuthStore(pinia).logoutLocally()
  await router.push({ name: 'login', query: { redirect: redirectPath } })
})

app.mount('#app')
