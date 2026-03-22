import axios from 'axios';
import type { AxiosResponse } from 'axios';
import type { ApiResponse } from '@/api/types';
import { useAuthStore } from '@/store/auth';
import router from '@/router';

const rawBaseURL = import.meta.env.VITE_API_BASE_URL?.trim();
const baseURL = rawBaseURL && rawBaseURL.length > 0
  ? rawBaseURL.replace(/\/+$/, '')
  : '/api/v1';

// Create a singleton axios instance
const http = axios.create({
  baseURL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Default request interceptor
http.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore();
    if (authStore.token) {
      config.headers['Authorization'] = `Bearer ${authStore.token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Advanced response interceptor
http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data;
    
    // SUCCESS
    if (res.code === '0000') {
      return res.data;
    }
    
    // AUTH ERROR (Expired or Invalid)
    if (res.code === '0401' || res.code === '0410') {
      const authStore = useAuthStore();
      authStore.clearAuth();
      router.push('/login');
    }
    
    // OTHER ERROR
    const errorMsg = res.info || '请求失败';
    console.error('[API Error]', errorMsg);
    return Promise.reject(new Error(errorMsg));
  },
  (error) => {
    console.error('[Network Error]', error.message);
    return Promise.reject(error);
  }
);

export default http;
