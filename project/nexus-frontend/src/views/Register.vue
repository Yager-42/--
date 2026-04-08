<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { registerAccount, sendSmsCode } from '@/api/auth'

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
  <div class="auth-page">
    <section class="auth-card surface-card">
      <button class="back-btn" type="button" @click="router.push('/login')">返回登录</button>
      <h1 class="text-large-title">创建账号</h1>
      <p class="text-secondary">完成注册后即可进入 Nexus</p>

      <label class="field">
        <span>手机号</span>
        <input v-model="phone" type="text" placeholder="请输入手机号">
      </label>

      <div class="row">
        <label class="field">
          <span>验证码</span>
          <input v-model="smsCode" type="text" placeholder="短信验证码">
        </label>
        <button class="secondary-btn code-btn" type="button" :disabled="sendingCode" @click="handleSendCode">
          {{ sendingCode ? '发送中...' : '发送验证码' }}
        </button>
      </div>

      <label class="field">
        <span>昵称</span>
        <input v-model="nickname" type="text" placeholder="你的昵称">
      </label>

      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" placeholder="请输入密码" @keyup.enter="handleRegister">
      </label>

      <p v-if="error" class="msg error">{{ error }}</p>
      <p v-if="success" class="msg success">{{ success }}</p>

      <button class="primary-btn" type="button" :disabled="loading" @click="handleRegister">
        {{ loading ? '注册中...' : '立即注册' }}
      </button>
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
  width: min(460px, 100%);
  padding: 20px;
  display: grid;
  gap: 12px;
}

.back-btn {
  justify-self: start;
  color: var(--brand-primary);
  font-weight: 700;
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

.row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: end;
}

.code-btn {
  min-width: 110px;
  padding: 0 12px;
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

@media (max-width: 520px) {
  .row {
    grid-template-columns: 1fr;
  }

  .code-btn {
    width: 100%;
  }
}
</style>
