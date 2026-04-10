<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { loginWithPassword } from '@/api/auth'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenField from '@/components/primitives/ZenField.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import FormMessage from '@/components/system/FormMessage.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const phone = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const success = ref('')

onMounted(() => {
  if (typeof route.query.phone === 'string') {
    phone.value = route.query.phone
  }
  if (route.query.registered === '1') {
    success.value = '注册成功，请直接登录。'
  }
})

const handleLogin = async () => {
  if (!phone.value.trim() || !password.value.trim()) {
    error.value = '请输入手机号和密码'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    const res = await loginWithPassword({
      phone: phone.value.trim(),
      password: password.value
    })
    authStore.setToken(res.token, res.userId, res.refreshToken)
    void router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败，请检查账号或密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-wrap">
    <header class="fixed inset-x-0 top-0 z-40 px-6 py-6">
      <div class="mx-auto flex w-full max-w-screen-2xl items-center justify-between">
        <button type="button" class="text-2xl font-semibold tracking-tight text-on-surface" @click="router.push('/login')">
          Nexus
        </button>
        <ZenIcon name="help_outline" :size="22" class="text-on-surface-variant" />
      </div>
    </header>

    <main class="flex min-h-screen items-center justify-center px-6 pb-12 pt-24">
      <div class="w-full max-w-md">
        <div class="paper-panel ghost-border relative overflow-hidden p-8 sm:p-10">
          <div class="mb-10 text-center">
            <h1 class="text-3xl font-bold tracking-tight text-on-surface">Welcome Back</h1>
            <p class="mt-2 text-sm tracking-wide text-on-surface-variant">
              登录后返回你刚才浏览的内容与连接关系。
            </p>
          </div>

          <form class="grid gap-5" @submit.prevent="handleLogin">
            <ZenField v-model="phone" label="手机号" placeholder="请输入手机号" />
            <ZenField
              v-model="password"
              label="密码"
              type="password"
              placeholder="请输入密码"
            />

            <FormMessage v-if="error" tone="error" :message="error" />
            <FormMessage v-if="success" tone="success" :message="success" />

            <ZenButton variant="primary" block type="submit" :disabled="loading">
              {{ loading ? '登录中...' : '登录' }}
            </ZenButton>
          </form>

          <div class="relative my-10">
            <div class="absolute inset-0 flex items-center">
              <div class="w-full border-t border-outline-variant/15" />
            </div>
            <div class="relative flex justify-center">
              <span class="bg-white px-4 text-[11px] font-semibold uppercase tracking-[0.22em] text-outline">
                Or connect via
              </span>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-4">
            <button
              type="button"
              class="flex items-center justify-center gap-3 rounded-2xl bg-surface-container-low py-3.5 transition hover:bg-secondary-container"
            >
              <span class="text-sm font-medium text-on-surface-variant">Google</span>
            </button>
            <button
              type="button"
              class="flex items-center justify-center gap-3 rounded-2xl bg-surface-container-low py-3.5 transition hover:bg-secondary-container"
            >
              <span class="text-sm font-medium text-on-surface-variant">Apple</span>
            </button>
          </div>

          <p class="mt-10 text-center text-sm text-on-surface-variant">
            还没有账号？
            <button
              type="button"
              class="font-semibold text-primary underline-offset-4 hover:underline"
              @click="router.push('/register')"
            >
              立即注册
            </button>
          </p>
        </div>

        <div class="pointer-events-none mt-12 flex h-24 items-center justify-center overflow-hidden opacity-10 select-none">
          <span class="whitespace-nowrap text-6xl font-extrabold tracking-tight">CURATED SPACES</span>
        </div>
      </div>
    </main>
  </div>
</template>
