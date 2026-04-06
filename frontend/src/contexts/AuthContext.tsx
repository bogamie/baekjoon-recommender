import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  ReactNode,
} from 'react'
import client from '../api/client'
import { getMe, User } from '../api/user'

interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  login: (accessToken: string, refreshToken: string) => Promise<void>
  logout: () => void
  setUser: (user: User) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    const token = localStorage.getItem('accessToken')
    if (!token) {
      setReady(true)
      return
    }
    getMe()
      .then((res) => setUser(res.data))
      .catch((err) => {
        if (err.response?.status === 401) {
          localStorage.removeItem('accessToken')
          localStorage.removeItem('refreshToken')
        }
      })
      .finally(() => setReady(true))
  }, [])

  const login = useCallback(async (accessToken: string, refreshToken: string) => {
    localStorage.setItem('accessToken', accessToken)
    localStorage.setItem('refreshToken', refreshToken)
    const res = await getMe()
    setUser(res.data)
  }, [])

  const logout = useCallback(() => {
    const refreshToken = localStorage.getItem('refreshToken')
    if (refreshToken) {
      client.post('/api/auth/logout', { refreshToken }).catch(() => {})
    }
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    setUser(null)
  }, [])

  useEffect(() => {
    const theme = user?.theme || localStorage.getItem('theme') || 'light'
    if (user?.theme) {
      localStorage.setItem('theme', user.theme)
    }
    document.documentElement.setAttribute('data-theme', theme)
  }, [user?.theme])

  if (!ready) return null

  return (
    <AuthContext.Provider
      value={{ user, isAuthenticated: user !== null, login, logout, setUser }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
