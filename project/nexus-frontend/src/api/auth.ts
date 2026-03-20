import http from '@/utils/http';
import type { ApiResponse, AuthTokenResponseDTO } from './types';

export const loginWithPassword = (data: any) => {
  return http.post<ApiResponse<AuthTokenResponseDTO>>('/auth/login/password', data);
}

export const sendSmsCode = (data: any) => {
  return http.post<ApiResponse<any>>('/auth/sms/send', data);
}
