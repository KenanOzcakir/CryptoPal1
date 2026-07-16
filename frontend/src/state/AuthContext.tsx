import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { api, tokenStore } from '../api/client'
import type { AuthUser } from '../api/types'

/**
 * Who is logged in.
 *
 * The token lives in localStorage so a page refresh does not throw the user out. That is a
 * deliberate trade: localStorage is readable by any script on the origin, so an XSS bug
 * would expose the token. For a simulated trading app with play money it is the right
 * call, and the backend backs it up by keeping sessions revocable, which is the whole
 * reason it uses opaque Redis tokens rather than JWTs. A real money app would want an
 * httpOnly cookie instead, which the backend would have to be taught to set.
 */

const USER_KEY = 'cryptopal.user'

interface AuthState {
  user: AuthUser | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

function readStoredUser(): AuthUser | null {
  // A token with no user is useless to the UI, so both are read together and either both
  // survive a refresh or neither does.
  const raw = localStorage.getItem(USER_KEY)
  if (!raw || !tokenStore.get()) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(readStoredUser)

  const remember = useCallback((token: string, nextUser: AuthUser) => {
    tokenStore.set(token)
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser))
    setUser(nextUser)
  }, [])

  const forget = useCallback(() => {
    tokenStore.clear()
    localStorage.removeItem(USER_KEY)
    setUser(null)
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await api.login(email, password)
      remember(response.token, response.user)
    },
    [remember],
  )

  const register = useCallback(
    async (email: string, password: string) => {
      // Registration returns a token too, so a new account is logged in already rather
      // than being sent back to a form to retype what it just typed.
      const response = await api.register(email, password)
      remember(response.token, response.user)
    },
    [remember],
  )

  const logout = useCallback(async () => {
    try {
      // Tell the server first, so the token is actually dead rather than merely forgotten
      // here. This is the half that a JWT could not do.
      await api.logout()
    } catch {
      // An expired or already-invalid token fails this call, which is not a reason to
      // keep the user logged in on screen. Clearing regardless is the honest outcome.
    }
    forget()
  }, [forget])

  const value = useMemo(() => ({ user, login, register, logout }), [user, login, register, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return context
}
