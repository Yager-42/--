<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { loginWithPassword } from '@/api/auth'

const router = useRouter()
const authStore = useAuthStore()

const account = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

const handleLogin = async () => {
  if (!account.value || !password.value) return
  
  loading.value = true
  errorMsg.value = ''
  
  try {
    const res: any = await loginWithPassword({
      account: account.value,
      password: password.value
    })
    
    // Save token and navigate
    authStore.setToken(res.token)
    router.push('/')
  } catch (err: any) {
    errorMsg.value = err.message || '登录失败，请检查账号密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-container">
      <div class="apple-logo"></div>
      <h1 class="text-large-title">登录以使用 Nexus</h1>
      <p class="text-body text-secondary">极简、安全、私密的社交体验。</p>
      
      <div class="form-section">
        <div class="input-group">
          <input 
            v-model="account" 
            type="text" 
            placeholder="手机号 / 邮箱 / 账号" 
            class="apple-input"
          />
        </div>
        <div class="input-group">
          <input 
            v-model="password" 
            type="password" 
            placeholder="密码" 
            class="apple-input"
            @keyup.enter="handleLogin"
          />
        </div>
        
        <p v-if="errorMsg" class="error-text">{{ errorMsg }}</p>
        
        <button 
          class="apple-btn" 
          :disabled="loading" 
          @click="handleLogin"
        >
          <span v-if="!loading">继续</span>
          <span v-else class="loading-spinner"></span>
        </button>
      </div>
      
      <div class="footer-links">
        <span class="text-secondary">没有账号？</span>
        <a href="#" class="link">立即注册</a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: var(--apple-bg);
  padding: 24px;
}

.login-container {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.apple-logo {
  font-size: 64px;
  margin-bottom: 8px;
  color: var(--apple-text);
}

.form-section {
  width: 100%;
  margin-top: 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  width: 100%;
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

.apple-btn {
  width: 100%;
  height: 52px;
  background-color: var(--apple-accent);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  justify-content: center;
  align-items: center;
  transition: opacity 0.2s ease, transform 0.1s ease;
}

.apple-btn:active {
  transform: scale(0.98);
}

.apple-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-text {
  color: #ff3b30;
  font-size: 14px;
  margin-top: -8px;
}

.footer-links {
  margin-top: 40px;
  font-size: 15px;
}

.link {
  color: var(--apple-accent);
  text-decoration: none;
  font-weight: 500;
}

/* Loading spinner */
.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2.5px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
