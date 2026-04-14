<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { loginWithPassword } from '@/api/auth'
import PrototypeAuthShell from '@/components/prototype/PrototypeAuthShell.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'

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

const canSubmit = computed(() => phone.value.trim().length > 0 && password.value.trim().length > 0)

const handleLogin = async () => {
  if (!canSubmit.value) {
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
  <PrototypeAuthShell>
    <div class="grid w-full max-w-content grid-cols-1 overflow-hidden rounded-[2rem] border border-prototype-line bg-prototype-surface lg:grid-cols-[1.05fr,0.95fr]">
      <div class="relative hidden min-h-[42rem] overflow-hidden bg-prototype-bg lg:block">
        <img
          alt="Quiet interior"
          class="absolute inset-0 h-full w-full object-cover opacity-85"
          src="https://lh3.googleusercontent.com/aida-public/AB6AXuCtz72u02xqM9fCGeRe4fpcPahXntM8bjZy1WrYyKcOPKV6yW5glsoCKf5UYsZzVlXFXA_lLmnnwLw3Zei6fy4MJNTY3Wcx8TFRLHVW8WYGqI_PsTCEingPkEUx9tY-MPqOWkA_vt9F7o2BorpbhuF75AOalEZZyA0Fd7mf76KbzsZjLTzRtZ3TsMuyM-_xHU2GDYieWmqNIw2bxxEMwCHjZR2C87xyBiPke98hf-nHVb9OvXaXXpN4EINegLco1Vry245klBgDOiXS"
        >
        <div class="absolute inset-0 bg-gradient-to-t from-[rgba(25,24,20,0.58)] via-transparent to-transparent" />
        <div class="absolute inset-x-0 bottom-0 p-10 text-prototype-surface">
          <p class="mb-3 text-[11px] font-semibold uppercase tracking-[0.24em]">Nexus</p>
          <h1 class="max-w-[12ch] font-headline text-5xl tracking-[-0.05em]">
            Enter a quieter sanctuary.
          </h1>
          <p class="mt-4 max-w-md text-sm leading-7 text-white/80">
            Resume your gallery with a calmer, prototype-aligned entry point designed for desktop reading.
          </p>
        </div>
      </div>

      <div class="flex flex-col justify-center px-8 py-10 md:px-12 md:py-14">
        <form class="space-y-8" @submit.prevent="handleLogin">
          <div class="space-y-3">
            <button
              type="button"
              class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted transition hover:text-prototype-ink"
              @click="router.push('/')"
            >
              Nexus
            </button>
            <h2 class="font-headline text-4xl tracking-[-0.04em] text-prototype-ink md:text-5xl">
              Welcome back
            </h2>
            <p class="text-sm leading-7 text-prototype-muted">
              Enter your phone number and password to return to your gallery.
            </p>
          </div>

          <div class="space-y-6">
            <div class="space-y-2">
              <label class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">Phone Number</label>
              <div class="flex gap-2">
                <div class="flex items-center gap-2 rounded-full border border-prototype-line bg-prototype-bg px-4 py-4">
                  <span class="text-sm font-medium text-prototype-ink">+86</span>
                  <ZenIcon name="expand_more" :size="16" class="text-prototype-muted" />
                </div>
                <input
                  v-model="phone"
                  type="tel"
                  inputmode="tel"
                  autocomplete="tel"
                  class="flex-grow rounded-full border border-prototype-line bg-prototype-bg px-6 py-4 text-lg tracking-wide text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
                  placeholder="138 0000 0000"
                >
              </div>
            </div>

            <div class="space-y-2">
              <div class="flex items-center justify-between gap-4">
                <label class="text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">Password</label>
                <button type="button" class="text-xs font-semibold text-prototype-accent transition hover:text-prototype-ink">
                  Forgot Password?
                </button>
              </div>
              <input
                v-model="password"
                type="password"
                autocomplete="current-password"
                class="w-full rounded-full border border-prototype-line bg-prototype-bg px-6 py-4 text-lg tracking-wide text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
                placeholder="Enter your password"
              >
            </div>

            <p
              v-if="error"
              class="rounded-2xl border border-error/15 bg-[rgba(158,66,44,0.08)] px-4 py-3 text-sm leading-6 text-error"
              role="alert"
            >
              {{ error }}
            </p>

            <p
              v-if="success"
              class="rounded-2xl border border-tertiary/15 bg-[rgba(95,98,62,0.08)] px-4 py-3 text-sm leading-6 text-tertiary"
            >
              {{ success }}
            </p>

            <button
              type="submit"
              class="w-full rounded-full bg-prototype-ink py-4 text-sm font-semibold tracking-[0.12em] text-prototype-surface transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="loading || !canSubmit"
            >
              {{ loading ? 'Signing in...' : 'Enter Nexus' }}
            </button>
          </div>

          <div class="relative py-2">
            <div class="absolute inset-0 flex items-center">
              <div class="w-full border-t border-prototype-line" />
            </div>
            <div class="relative flex justify-center">
              <span class="bg-prototype-surface px-4 text-xs uppercase tracking-[0.2em] text-prototype-muted">
                Or connect via
              </span>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-4">
            <button
              type="button"
              class="rounded-[1rem] bg-prototype-bg px-4 py-3 text-sm font-medium text-prototype-muted transition hover:bg-secondary-container hover:text-prototype-ink"
            >
              Google
            </button>
            <button
              type="button"
              class="rounded-[1rem] bg-prototype-bg px-4 py-3 text-sm font-medium text-prototype-muted transition hover:bg-secondary-container hover:text-prototype-ink"
            >
              Apple
            </button>
          </div>

          <div class="border-t border-prototype-line pt-8">
            <p class="text-center text-sm leading-7 text-prototype-muted">
              By continuing, you agree to our Manifesto and Terms.
            </p>
            <p class="mt-4 text-center text-sm text-prototype-muted">
              New to the gallery?
              <button
                type="button"
                class="font-semibold text-prototype-accent transition hover:text-prototype-ink"
                @click="router.push('/register')"
              >
                Request access
              </button>
            </p>
          </div>
        </form>
      </div>
    </div>
  </PrototypeAuthShell>
</template>
