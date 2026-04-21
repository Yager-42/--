<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { registerAccount } from '@/api/auth'
import PrototypeAuthShell from '@/components/prototype/PrototypeAuthShell.vue'
import FormMessage from '@/components/system/FormMessage.vue'

const router = useRouter()

const phone = ref('')
const password = ref('')
const nickname = ref('')
const countryCode = ref('+86')
const agreed = ref(true)
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const success = ref('')

const handleRegister = async () => {
  if (!nickname.value.trim() || !phone.value.trim() || !password.value.trim()) {
    error.value = '请完整填写昵称、手机号和密码'
    return
  }

  if (!agreed.value) {
    error.value = '请先同意条款与隐私政策'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    await registerAccount({
      phone: phone.value.trim(),
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
  <PrototypeAuthShell>
    <div class="grid w-full max-w-content grid-cols-1 items-center gap-12 rounded-[2rem] border border-prototype-line bg-prototype-surface p-8 lg:grid-cols-[0.9fr,1.1fr] lg:p-10">
      <div class="hidden space-y-8 lg:block">
        <div class="relative aspect-[4/5] overflow-hidden rounded-[1.75rem] bg-prototype-bg">
          <img
            class="absolute inset-0 h-full w-full object-cover opacity-85"
            src="https://lh3.googleusercontent.com/aida-public/AB6AXuDOnn_1JLc9gGoF6jb-D6n58TH6OHk1CmRjIQ-MQhd5FPzrZ-FlKDdKEqOtjnuCDw7BkNkluWCjoxNc6SOW4bjRakKymHWgmZDzvPNF3TVEJ93GXdEflrnw4p4q0FSsfaCzwneo291_9APKOCR2WOYKadi-pbde4zDV2fHh86esFuQTdu4CQ5qZY6mxqamEtIxKTua1zCC9L3egsugP1zrudDAHC1j2kQwe6LDhbCZxtPSZxZCR5gjetntrzQeen-0cKZ5R5pjc3E8a"
            alt="Minimalist gallery interior"
          >
          <div class="absolute inset-0 bg-gradient-to-t from-[rgba(25,24,20,0.48)] to-transparent" />
        </div>
        <div class="space-y-3">
          <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">Begin your curation</p>
          <h2 class="font-headline text-4xl tracking-[-0.04em] text-prototype-ink">Your digital sanctuary.</h2>
          <p class="max-w-sm text-sm leading-7 text-prototype-muted">
            在更克制、更安静的界面里完成注册，把注意力留给内容与表达本身。
          </p>
        </div>
      </div>

      <div class="mx-auto w-full max-w-xl">
        <div class="space-y-8">
          <header class="space-y-3">
            <button
              type="button"
              class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted transition hover:text-prototype-ink"
              @click="router.push('/login')"
            >
              Nexus
            </button>
             <h1 class="font-headline text-4xl tracking-[-0.04em] text-prototype-ink md:text-5xl">
               Create Account
             </h1>
            <p class="text-sm leading-7 text-prototype-muted">
              Begin your curation journey today.
            </p>
          </header>

          <form class="space-y-6" @submit.prevent="handleRegister">
            <div class="space-y-2">
              <label class="block text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted" for="nickname">Nickname</label>
              <input
                id="nickname"
                v-model="nickname"
                type="text"
                placeholder="输入你想使用的昵称"
                class="w-full rounded-full border border-prototype-line bg-prototype-bg px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
              >
            </div>

            <div class="space-y-2">
              <label class="block text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted" for="phone">Phone Number</label>
              <div class="flex gap-3">
                <div class="group relative">
                  <select
                    v-model="countryCode"
                    class="h-full cursor-pointer appearance-none rounded-full border border-prototype-line bg-prototype-bg py-4 pl-5 pr-10 font-medium text-prototype-ink outline-none transition focus:border-prototype-ink"
                  >
                    <option value="+86">CN +86</option>
                    <option value="+1">US +1</option>
                    <option value="+44">UK +44</option>
                  </select>
                  <span class="material-symbols-outlined pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-prototype-muted">expand_more</span>
                </div>
                <input
                  id="phone"
                  v-model="phone"
                  type="tel"
                  placeholder="请输入手机号"
                  class="flex-1 rounded-full border border-prototype-line bg-prototype-bg px-5 py-4 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
                >
              </div>
            </div>

            <div class="space-y-2">
              <label class="block text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted" for="password">Password</label>
              <div class="relative">
                <input
                  id="password"
                  v-model="password"
                  :type="showPassword ? 'text' : 'password'"
                  placeholder="••••••••"
                  class="w-full rounded-full border border-prototype-line bg-prototype-bg px-5 py-4 pr-14 text-prototype-ink outline-none transition placeholder:text-prototype-muted/70 focus:border-prototype-ink"
                >
                <button
                  type="button"
                  class="material-symbols-outlined absolute right-5 top-1/2 -translate-y-1/2 text-prototype-muted"
                  @click="showPassword = !showPassword"
                >
                  {{ showPassword ? 'visibility_off' : 'visibility' }}
                </button>
              </div>
            </div>

            <label class="flex items-start gap-3 py-2">
              <div class="relative flex items-center pt-1">
                <input
                  v-model="agreed"
                  type="checkbox"
                  class="h-5 w-5 cursor-pointer rounded border-prototype-line bg-prototype-bg text-prototype-accent focus:ring-prototype-accent/20"
                >
              </div>
              <span class="text-sm leading-relaxed text-prototype-muted">
                我同意 Terms 和 Privacy Policy。
              </span>
            </label>

            <FormMessage v-if="error" tone="error" :message="error" />
            <FormMessage v-if="success" tone="success" :message="success" />

            <button
              type="submit"
              class="w-full rounded-full bg-prototype-ink py-5 text-sm font-semibold tracking-[0.12em] text-prototype-surface transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-70"
              :disabled="loading"
            >
              {{ loading ? '注册中...' : 'Register' }}
            </button>

            <div class="pt-4 text-center">
              <p class="text-prototype-muted">
                Already part of the sanctuary?
                <button
                  type="button"
                  class="ml-1 font-semibold text-prototype-accent transition hover:text-prototype-ink"
                  @click="router.push('/login')"
                >
                  Sign In
                </button>
              </p>
            </div>
          </form>
        </div>
      </div>
    </div>
  </PrototypeAuthShell>
</template>
