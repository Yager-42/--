export type AuthTokenResponse = {
  userId: number
  tokenName: string
  tokenPrefix: string
  token: string
  refreshToken: string
}

export type AuthLoginPayload = {
  phone: string
  password: string
}

export type AuthRegisterPayload = {
  phone: string
  password: string
  nickname: string
  avatarUrl?: string
}

export type AuthMe = {
  userId: number
  phone: string
  status: number
  nickname: string
  avatarUrl?: string
}

export type AuthChangePasswordPayload = {
  oldPassword: string
  newPassword: string
}
