import http from '@/utils/http';
import type { AuthTokenResponseDTO, RawAuthTokenResponseDTO } from './types';

export interface PasswordLoginRequestDTO {
  phone: string;
  password: string;
}

export interface RegisterRequestDTO {
  phone: string;
  password: string;
  nickname: string;
  avatarUrl: string;
}

export interface RegisterResponseDTO {
  userId: string;
}

interface RawAuthMeResponseDTO {
  userId: number | string;
  phone: string;
  status: string;
  nickname: string;
  avatarUrl?: string;
}

export interface AuthMeResponseDTO {
  userId: string;
  phone: string;
  status: string;
  nickname: string;
  avatarUrl: string;
}

export interface ChangePasswordRequestDTO {
  oldPassword: string;
  newPassword: string;
}

const normalizeAuthTokenResponse = (data: RawAuthTokenResponseDTO): AuthTokenResponseDTO => ({
  token: data.token,
  refreshToken: data.refreshToken,
  userId: String(data.userId)
});

const normalizeAuthMeResponse = (data: RawAuthMeResponseDTO): AuthMeResponseDTO => ({
  userId: String(data.userId),
  phone: data.phone,
  status: data.status,
  nickname: data.nickname,
  avatarUrl: data.avatarUrl || ''
});

export const loginWithPassword = async (
  data: PasswordLoginRequestDTO
): Promise<AuthTokenResponseDTO> => {
  const response = await http.post<RawAuthTokenResponseDTO>('/auth/login/password', data);
  return normalizeAuthTokenResponse(response);
};

export const registerAccount = async (
  data: RegisterRequestDTO
): Promise<RegisterResponseDTO> => {
  const response = await http.post<{ userId: number | string }>('/auth/register', data);
  return {
    userId: String(response.userId)
  };
};

export interface RefreshTokenRequestDTO {
  refreshToken: string;
}

export const refreshAccessToken = async (
  data: RefreshTokenRequestDTO
): Promise<AuthTokenResponseDTO> => {
  const response = await http.post<RawAuthTokenResponseDTO>('/auth/refresh', data);
  return normalizeAuthTokenResponse(response);
};

export const fetchAuthMe = async (): Promise<AuthMeResponseDTO> => {
  const response = await http.get<RawAuthMeResponseDTO>('/auth/me');
  return normalizeAuthMeResponse(response);
};

export const logout = async (): Promise<void> => {
  await http.post<null>('/auth/logout');
};

export const changePassword = async (data: ChangePasswordRequestDTO): Promise<void> => {
  await http.post<null>('/auth/password/change', data);
};
