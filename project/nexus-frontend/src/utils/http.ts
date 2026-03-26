import axios from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import type { ApiResponse } from '@/api/types';
import { useAuthStore } from '@/store/auth';
import router from '@/router';

const rawBaseURL = import.meta.env.VITE_API_BASE_URL?.trim();
const baseURL = rawBaseURL && rawBaseURL.length > 0
  ? rawBaseURL.replace(/\/+$/, '')
  : '/api/v1';

// Create a singleton axios instance
const client = axios.create({
  baseURL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Default request interceptor
client.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const authStore = useAuthStore();
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

const unwrapResponse = <T>(response: AxiosResponse<ApiResponse<T>>): T => {
  const res = response.data;

  if (res.code === '0000') {
    return res.data;
  }

  if (res.code === '0401' || res.code === '0410') {
    const authStore = useAuthStore();
    authStore.clearAuth();
    void router.push('/login');
  }

  const errorMsg = res.info || '请求失败';
  console.error('[API Error]', errorMsg);
  throw new Error(errorMsg);
};

const request = async <T>(promise: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> => {
  try {
    const response = await promise;
    return unwrapResponse(response);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[Network Error]', message);
    return Promise.reject(error);
  }
};

const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request(client.get<ApiResponse<T>>(url, config));
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request(client.post<ApiResponse<T>>(url, data, config));
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request(client.put<ApiResponse<T>>(url, data, config));
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request(client.patch<ApiResponse<T>>(url, data, config));
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request(client.delete<ApiResponse<T>>(url, config));
  }
};

export default http;
