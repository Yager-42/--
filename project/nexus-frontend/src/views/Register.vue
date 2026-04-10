<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { registerAccount, sendSmsCode } from '@/api/auth'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenField from '@/components/primitives/ZenField.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import FormMessage from '@/components/system/FormMessage.vue'

const router = useRouter()

const phone = ref('')
const smsCode = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)
const sendingCode = ref(false)
const error = ref('')
const success = ref('')

const handleSendCode = async () => {
  if (!phone.value.trim()) {
    error.value = '请先输入手机号'
    return
  }

  sendingCode.value = true
  error.value = ''
  success.value = ''

  try {
    await sendSmsCode({
      phone: phone.value.trim(),
      bizType: 'REGISTER'
    })
    success.value = '验证码已发送，请注意查收。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '验证码发送失败'
  } finally {
    sendingCode.value = false
  }
}

const handleRegister = async () => {
  if (!phone.value.trim() || !smsCode.value.trim() || !password.value.trim() || !nickname.value.trim()) {
    error.value = '请完整填写手机号、验证码、昵称和密码'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    await registerAccount({
      phone: phone.value.trim(),
      smsCode: smsCode.value.trim(),
      password: password.value,
      nickname: nickname.value.trim(),
      avatarUrl: ''
    })

    void router.push({
      path: '/login',
      query: {
        phone: phone.value.trim(),
        registered: '1'
      }
    })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '注册失败'
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
        <span class="hidden text-sm font-medium tracking-wide text-on-surface-variant md:inline">Step 1 of 1</span>
      </div>
    </header>

    <main class="flex min-h-screen items-center justify-center px-6 py-24 md:py-32">
      <div class="grid w-full max-w-5xl grid-cols-1 items-center gap-12 lg:grid-cols-2 lg:gap-16">
        <div class="hidden lg:grid lg:gap-8">
          <div class="relative aspect-[4/5] overflow-hidden rounded-3xl bg-surface-container-low">
            <div class="absolute inset-0 bg-gradient-to-t from-primary/20 to-transparent" />
            <div class="absolute inset-0 bg-[radial-gradient(circle_at_50%_20%,rgba(255,255,255,0.65),transparent_42%),linear-gradient(180deg,rgba(255,255,255,0.1),rgba(97,95,80,0.15))]" />
          </div>
          <div class="grid gap-3">
            <h2 class="text-3xl font-bold tracking-tight text-on-surface">Your digital sanctuary.</h2>
            <p class="max-w-sm text-lg leading-8 text-on-surface-variant">
              加入一个更适合慢速浏览与表达的内容网络，在同一页完成真实注册。
            </p>
          </div>
        </div>

        <div class="mx-auto w-full max-w-md">
          <div class="grid gap-8">
            <header class="grid gap-2">
              <h1 class="text-4xl font-semibold tracking-tight text-on-surface">Create Account</h1>
              <p class="text-on-surface-variant">Begin your curation journey today.</p>
            </header>

            <form class="grid gap-5" @submit.prevent="handleRegister">
              <ZenField v-model="nickname" label="昵称" placeholder="输入你想使用的昵称" />
              <ZenField v-model="phone" label="手机号" placeholder="请输入手机号" />

              <div class="grid gap-3 sm:grid-cols-[minmax(0,1fr),auto] sm:items-end">
                <ZenField v-model="smsCode" label="验证码" placeholder="短信验证码" />
                <ZenButton
                  variant="secondary"
                  class="min-w-[132px]"
                  :disabled="sendingCode"
                  @click="handleSendCode"
                >
                  {{ sendingCode ? '发送中...' : '发送验证码' }}
                </ZenButton>
              </div>

              <label class="grid gap-2">
                <span class="text-xs font-semibold uppercase tracking-[0.22em] text-on-surface-variant">密码</span>
                <div class="relative">
                  <ZenField
                    v-model="password"
                    type="password"
                    placeholder="请输入密码"
                    class="pr-12"
                  />
                  <ZenIcon name="visibility" :size="18" class="pointer-events-none absolute right-5 top-1/2 -translate-y-1/2 text-outline" />
                </div>
              </label>

              <FormMessage v-if="error" tone="error" :message="error" />
              <FormMessage v-if="success" tone="success" :message="success" />

              <ZenButton variant="primary" block type="submit" :disabled="loading">
                {{ loading ? '注册中...' : '立即注册' }}
              </ZenButton>

              <p class="pt-2 text-center text-on-surface-variant">
                已经有账号？
                <button
                  type="button"
                  class="ml-1 font-semibold text-primary hover:underline"
                  @click="router.push('/login')"
                >
                  登录
                </button>
              </p>
            </form>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>
