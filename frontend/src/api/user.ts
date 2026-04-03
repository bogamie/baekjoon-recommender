import client from './client'

export interface User {
  id: number
  email: string
  username: string
  solvedacHandle?: string
  theme?: string
  includeForeign?: boolean
}

export function getMe() {
  return client.get<User>('/api/users/me')
}

export function updateSolvedac(handle: string) {
  return client.put('/api/users/me/solvedac', { handle })
}

export function syncData() {
  return client.post('/api/users/me/sync')
}

export function updateSettings(settings: { theme?: string; includeForeign?: boolean }) {
  return client.put<User>('/api/users/me/settings', settings)
}

export function deleteAccount(password: string) {
  return client.delete('/api/users/me', { data: { password } })
}
