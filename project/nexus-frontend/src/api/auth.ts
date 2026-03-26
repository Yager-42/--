import http from '@/utils/http';
import type { AuthTokenResponseDTO, RawAuthTokenResponseDTO } from './types';

export interface PasswordLoginRequestDTO {
  phone: string;
  password: string;
}

export interface AuthSmsSendRequestDTO {
  phone: string;
  bizType: string;
}

export interface AuthSmsSendResponseDTO {
  expireSeconds: number;
}

export interface RegisterRequestDTO {
  phone: string;
  smsCode: string;
  password: string;
  nickname: string;
  avatarUrl: string;
}

export interface RegisterResponseDTO {
  userId: string;
}

const normalizeAuthTokenResponse = (data: RawAuthTokenResponseDTO): AuthTokenResponseDTO => ({
  token: data.token,
  userId: String(data.userId)
});

export const loginWithPassword = async (
  data: PasswordLoginRequestDTO
): Promise<AuthTokenResponseDTO> => {
  const response = await http.post<RawAuthTokenResponseDTO>('/auth/login/password', data);
  return normalizeAuthTokenResponse(response);
};

export const sendSmsCode = (
  data: AuthSmsSendRequestDTO
): Promise<AuthSmsSendResponseDTO> => {
  return http.post<AuthSmsSendResponseDTO>('/auth/sms/send', data);
};

export const registerAccount = async (
  data: RegisterRequestDTO
): Promise<RegisterResponseDTO> => {
  const response = await http.post<{ userId: number | string }>('/auth/register', data);
  return {
    userId: String(response.userId)
  };
};
