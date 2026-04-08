<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { loginWithPassword } from '@/api/auth'

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
  <div class="auth-page">
    <section class="auth-card surface-card">
      <h1 class="text-large-title">登录 Nexus</h1>
      <p class="text-secondary">连接你的内容与社交关系</p>

      <label class="field">
        <span>手机号</span>
        <input v-model="phone" type="text" placeholder="请输入手机号">
      </label>

      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" placeholder="请输入密码" @keyup.enter="handleLogin">
      </label>

      <p v-if="error" class="msg error">{{ error }}</p>
      <p v-if="success" class="msg success">{{ success }}</p>

      <button class="primary-btn" type="button" :disabled="loading" @click="handleLogin">
        {{ loading ? '登录中...' : '登录' }}
      </button>

      <p class="switcher">
        还没有账号？
        <button class="link-btn" type="button" @click="router.push('/register')">立即注册</button>
      </p>
    </section>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 16px;
}

.auth-card {
  width: min(420px, 100%);
  padding: 20px;
  display: grid;
  gap: 12px;
}

.field {
  display: grid;
  gap: 6px;
}

.field span {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.field input {
  min-height: 44px;
  border-radius: 12px;
  border: 1px solid var(--border-soft);
  background: #fff;
  padding: 0 12px;
  outline: none;
}

.msg {
  font-size: 0.9rem;
}

.msg.error {
  color: var(--brand-danger);
}

.msg.success {
  color: #15803d;
}

.switcher {
  font-size: 0.92rem;
  color: var(--text-secondary);
}

.link-btn {
  color: var(--brand-primary);
  font-weight: 700;
}
</style>
