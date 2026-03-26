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
const errorMsg = ref('')
const successMsg = ref('')

const handleSendCode = async () => {
  if (!phone.value.trim()) {
    errorMsg.value = '请先输入手机号'
    return
  }

  sendingCode.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    await sendSmsCode({
      phone: phone.value.trim(),
      bizType: 'REGISTER'
    })
    successMsg.value = '验证码已发送，请注意查收'
  } catch (err: any) {
    errorMsg.value = err.message || '验证码发送失败，请稍后再试'
  } finally {
    sendingCode.value = false
  }
}

const handleRegister = async () => {
  if (!phone.value.trim() || !smsCode.value.trim() || !password.value.trim() || !nickname.value.trim()) {
    errorMsg.value = '请把手机号、验证码、昵称和密码填完整'
    return
  }

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    await registerAccount({
      phone: phone.value.trim(),
      smsCode: smsCode.value.trim(),
      password: password.value,
      nickname: nickname.value.trim(),
      avatarUrl: ''
    })
    router.push({
      path: '/login',
      query: {
        phone: phone.value.trim(),
        registered: '1'
      }
    })
  } catch (err: any) {
    errorMsg.value = err.message || '注册失败，请检查输入信息'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <div class="register-container">
      <button class="back-link" @click="router.push('/login')">返回登录</button>
      <h1 class="text-large-title">创建你的 Nexus 账号</h1>
      <p class="text-body text-secondary">先完成注册，再回到登录页继续使用。</p>

      <div class="form-section">
        <div class="input-group">
          <input
            v-model="phone"
            type="text"
            placeholder="手机号"
            class="apple-input"
          />
        </div>

        <div class="input-row">
          <input
            v-model="smsCode"
            type="text"
            placeholder="短信验证码"
            class="apple-input"
          />
          <button class="apple-btn-secondary code-btn" :disabled="sendingCode" @click="handleSendCode">
            <span v-if="!sendingCode">发送验证码</span>
            <span v-else>发送中...</span>
          </button>
        </div>

        <div class="input-group">
          <input
            v-model="nickname"
            type="text"
            placeholder="昵称"
            class="apple-input"
          />
        </div>

        <div class="input-group">
          <input
            v-model="password"
            type="password"
            placeholder="密码"
            class="apple-input"
            @keyup.enter="handleRegister"
          />
        </div>

        <p v-if="errorMsg" class="error-text">{{ errorMsg }}</p>
        <p v-if="successMsg" class="success-text">{{ successMsg }}</p>

        <button class="apple-btn" :disabled="loading" @click="handleRegister">
          <span v-if="!loading">立即注册</span>
          <span v-else class="loading-text">注册中...</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: var(--apple-bg);
  padding: 24px;
}

.register-container {
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.back-link {
  width: fit-content;
  padding: 0;
  background: none;
  border: none;
  color: var(--apple-accent);
  font-size: 15px;
  cursor: pointer;
}

.form-section {
  width: 100%;
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  width: 100%;
}

.input-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 132px;
  gap: 12px;
}

.apple-input {
  width: 100%;
  height: 52px;
  background-color: var(--apple-bg);
  border: 1.5px solid #d2d2d7;
  border-radius: 12px;
  padding: 0 16px;
  font-size: 17px;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.apple-input:focus {
  border-color: var(--apple-accent);
  box-shadow: 0 0 0 4px rgba(0, 102, 204, 0.1);
}

.apple-btn,
.apple-btn-secondary {
  height: 52px;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.apple-btn {
  background-color: var(--apple-accent);
  color: white;
}

.apple-btn-secondary {
  background: #f5f5f7;
  color: var(--apple-text);
}

.apple-btn:disabled,
.apple-btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.code-btn {
  width: 132px;
}

.error-text {
  color: #ff3b30;
  font-size: 14px;
}

.success-text {
  color: #34c759;
  font-size: 14px;
}

.loading-text {
  display: inline-block;
}

@media (max-width: 480px) {
  .input-row {
    grid-template-columns: 1fr;
  }

  .code-btn {
    width: 100%;
  }
}
</style>
