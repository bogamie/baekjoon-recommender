import client from './client'

export interface LoginResponse {
  accessToken: string
  refreshToken: string
}

export interface SignupPayload {
  email: string
  username: string
  password: string
  solvedacHandle?: string
}

export function checkEmail(email: string) {
  return client.post<{ available: boolean }>('/api/auth/check-email', { email })
}

export function checkUsername(username: string) {
  return client.post<{ available: boolean }>('/api/auth/check-username', { username })
}

export function sendCode(email: string) {
  return client.post('/api/auth/send-code', { email })
}

export function verifyCode(email: string, code: string) {
  return client.post('/api/auth/verify-code', { email, code })
}

export function signup(payload: SignupPayload) {
  return client.post('/api/auth/signup', payload)
}

export function login(email: string, password: string) {
  return client.post<LoginResponse>('/api/auth/login', { email, password })
}

export function refresh(refreshToken: string) {
  return client.post<LoginResponse>('/api/auth/refresh', { refreshToken })
}

export function forgotPassword(email: string) {
  return client.post('/api/auth/forgot-password', { email })
}

export function resetPassword(email: string, code: string, newPassword: string) {
  return client.post('/api/auth/reset-password', { email, code, newPassword })
}
