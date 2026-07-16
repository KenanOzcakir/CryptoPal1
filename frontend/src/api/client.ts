import type {
  AiInsightResponse,
  ApiError,
  AuthResponse,
  ErrorCode,
  OrderRequest,
  OrderResponse,
  Portfolio,
  PriceQuote,
  TransactionView,
} from './types'

/**
 * The only place in the app that talks to the backend.
 *
 * Requests go to a relative /api path, never to an absolute host. In development Vite
 * proxies that to Spring Boot, and in production nginx does the same, so the browser is
 * always same-origin and there is no CORS anywhere in this project.
 */

const TOKEN_KEY = 'cryptopal.token'

/**
 * An error the backend explained.
 *
 * It keeps `code` alongside the message so callers can branch on the contract rather than
 * on English prose. Anything the backend did not explain (the network being down, a
 * gateway returning HTML) still lands here, with a code of INTERNAL_ERROR, so a caller
 * only ever has one error type to handle.
 */
export class CryptoPalError extends Error {
  readonly code: ErrorCode

  constructor(message: string, code: ErrorCode) {
    super(message)
    this.name = 'CryptoPalError'
    this.code = code
  }
}

/** The session token, which lives in localStorage so a refresh does not log the user out. */
export const tokenStore = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = tokenStore.get()

  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        // Only sent when there is one. The public market endpoints do not want it, and
        // sending an empty Bearer would be rejected rather than ignored.
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init.headers,
      },
    })
  } catch {
    // fetch only rejects when the request never completed: no network, DNS failure, the
    // backend not running. There is no response to read a code out of, so one is invented
    // here to keep the single error type promise.
    throw new CryptoPalError(
      'Could not reach the server. Check that the backend is running.',
      'INTERNAL_ERROR',
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  if (!response.ok) {
    throw await toError(response)
  }

  return (await response.json()) as T
}

async function toError(response: Response): Promise<CryptoPalError> {
  try {
    const body = (await response.json()) as ApiError
    if (body?.code && body?.message) {
      return new CryptoPalError(body.message, body.code)
    }
  } catch {
    // Not JSON at all. Happens if something other than the backend answers, for example a
    // proxy returning an HTML error page.
  }
  return new CryptoPalError(
    `Request failed with status ${response.status}`,
    response.status === 401 ? 'UNAUTHORIZED' : 'INTERNAL_ERROR',
  )
}

export const api = {
  register: (email: string, password: string) =>
    request<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  login: (email: string, password: string) =>
    request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  /** Public. Works with no token. */
  prices: () => request<PriceQuote[]>('/market/prices'),

  order: (order: OrderRequest) =>
    request<OrderResponse>('/orders', { method: 'POST', body: JSON.stringify(order) }),

  portfolio: () => request<Portfolio>('/portfolio'),

  transactions: () => request<TransactionView[]>('/transactions'),

  ask: (question: string) =>
    request<AiInsightResponse>('/ai/ask', {
      method: 'POST',
      body: JSON.stringify({ question }),
    }),
}
