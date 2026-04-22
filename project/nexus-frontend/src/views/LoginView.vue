<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '@/layouts/AuthLayout.vue'
import AppLogo from '@/components/common/AppLogo.vue'
import StatusMessage from '@/components/common/StatusMessage.vue'
import { fetchMe, loginByPassword, registerAccount } from '@/services/api/authApi'
import { useAuthStore } from '@/stores/auth'

const emit = defineEmits<{
  (event: 'login-submitted', payload: { phone: string; password: string }): void
}>()

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const authMode = ref<'login' | 'register'>('login')
const loginForm = reactive({
  nickname: '',
  phone: '',
  password: ''
})

const errorMessage = ref('')
const infoMessage = ref('')
const isSubmitting = ref(false)

const heading = computed(() => (authMode.value === 'login' ? '密码登录' : '注册账号'))
const submitLabel = computed(() => {
  if (isSubmitting.value) {
    return authMode.value === 'login' ? '登录中...' : '注册中...'
  }

  return authMode.value === 'login' ? '登录' : '完成注册'
})

function switchMode(mode: 'login' | 'register') {
  authMode.value = mode
  errorMessage.value = ''
  infoMessage.value = ''
}

async function handleSubmit() {
  if (!loginForm.phone.trim() || !loginForm.password.trim()) {
    errorMessage.value = '请输入手机号和密码'
    return
  }

  if (authMode.value === 'register' && !loginForm.nickname.trim()) {
    errorMessage.value = '请输入昵称'
    return
  }

  errorMessage.value = ''
  infoMessage.value = ''

  if (authMode.value === 'login') {
    emit('login-submitted', {
      phone: loginForm.phone.trim(),
      password: loginForm.password
    })
  }

  isSubmitting.value = true

  try {
    if (authMode.value === 'register') {
      await registerAccount({
        nickname: loginForm.nickname.trim(),
        phone: loginForm.phone.trim(),
        password: loginForm.password
      })
      infoMessage.value = '注册成功，请使用新账号登录'
      authMode.value = 'login'
      return
    }

    const loginResult = await loginByPassword({
      phone: loginForm.phone.trim(),
      password: loginForm.password
    })

    await authStore.completeLogin(loginResult)

    const me = await fetchMe()
    authStore.setCurrentUser({
      userId: me.userId,
      nickname: me.nickname,
      avatarUrl: me.avatarUrl
    })

    const redirectTarget = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirectTarget)
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : authMode.value === 'login'
          ? '登录失败，请稍后重试'
          : '注册失败，请稍后重试'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <template #hero>
      <AppLogo />
      <div class="mt-8 space-y-4">
        <h1 class="font-headline text-4xl font-semibold tracking-tight text-nx-text">
          从内容出发，连接真实关系。
        </h1>
        <p class="max-w-lg text-base leading-7 text-nx-text-muted">
          使用 Nexus 登录后进入时间线、搜索、主页与通知，体验一个以内容可读性为核心的社交前端。
        </p>
      </div>
    </template>

    <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-8 shadow-[var(--nx-shadow-card)]">
      <header class="space-y-4">
        <div>
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Account Access</p>
          <h2 class="font-headline text-3xl font-semibold text-nx-text">{{ heading }}</h2>
        </div>

        <div class="grid grid-cols-2 gap-2 rounded-full bg-nx-surface-muted p-1">
          <button
            data-test="auth-mode-login"
            type="button"
            class="min-h-11 rounded-full text-sm font-semibold transition"
            :class="authMode === 'login' ? 'bg-white text-nx-text shadow-sm' : 'text-nx-text-muted'"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            data-test="auth-mode-register"
            type="button"
            class="min-h-11 rounded-full text-sm font-semibold transition"
            :class="authMode === 'register' ? 'bg-white text-nx-text shadow-sm' : 'text-nx-text-muted'"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>
      </header>

      <form class="mt-8 space-y-5" @submit.prevent="handleSubmit">
        <div v-if="authMode === 'register'" class="space-y-2">
          <label class="text-sm font-medium text-nx-text" for="nickname">昵称</label>
          <input
            id="nickname"
            data-test="register-nickname-input"
            v-model="loginForm.nickname"
            autocomplete="nickname"
            class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            placeholder="为你的主页设置一个名字"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-nx-text" for="phone">手机号</label>
          <input
            id="phone"
            data-test="phone-input"
            v-model="loginForm.phone"
            autocomplete="username"
            class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            placeholder="请输入手机号"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-nx-text" for="password">密码</label>
          <input
            id="password"
            data-test="password-input"
            v-model="loginForm.password"
            type="password"
            :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'"
            class="h-12 w-full rounded-2xl border border-nx-border bg-white px-4 text-base text-nx-text outline-none transition focus:border-nx-primary focus:ring-2 focus:ring-blue-100"
            placeholder="请输入密码"
          />
        </div>

        <StatusMessage v-if="errorMessage" :message="errorMessage" tone="error" />
        <StatusMessage v-else-if="infoMessage" :message="infoMessage" />

        <button
          data-test="login-submit"
          type="submit"
          :disabled="isSubmitting"
          class="inline-flex h-12 w-full items-center justify-center rounded-full bg-nx-primary px-6 text-sm font-semibold text-white transition hover:brightness-105 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-nx-primary"
        >
          {{ submitLabel }}
        </button>
      </form>
    </section>
  </AuthLayout>
</template>
