import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/main.css'
import { useAuthStore } from '@/store/auth'
import { installUIMockRuntime } from '@/mocks/runtime'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

installUIMockRuntime(useAuthStore(pinia))

app.use(router)
app.mount('#app')
