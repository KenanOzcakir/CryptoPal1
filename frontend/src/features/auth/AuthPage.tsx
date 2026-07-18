import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { CryptoPalError } from '../../api/client'
import { Button, ErrorNote, Spinner } from '../../components/ui'
import { useAuth } from '../../state/AuthContext'

/**
 * Register and log in, on one screen with a toggle.
 *
 * One screen rather than two routes because the forms are the same two fields and the
 * commonest reason someone lands on either is that they picked the wrong one.
 */
export function AuthPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const auth = useAuth()
  const navigate = useNavigate()

  const registering = mode === 'register'

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (registering) {
        await auth.register(email, password)
      } else {
        await auth.login(email, password)
      }
      navigate('/', { replace: true })
    } catch (e) {
      // The backend writes these messages to be read by a user, so they are shown as they
      // are. Its login error is deliberately the same for a wrong password and an unknown
      // address, and rewording it here would undo that.
      setError(e instanceof CryptoPalError ? e.message : 'Something went wrong. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          {/* gradient badge with a soft glow -> matching the header logo treatment */}
          <div className="mb-3 inline-flex size-11 items-center justify-center rounded-xl bg-gradient-to-br from-accent to-accent-hover text-xl font-bold shadow-lg shadow-accent/40">
            L
          </div>
          {/* two-tone brand name -> gradient blue for Lumpa, ink for Crypto */}
          <h1 className="text-2xl font-semibold">
            <span className="bg-gradient-to-r from-accent-soft to-accent bg-clip-text text-transparent">
              Lumpa
            </span>
            <span className="text-ink">Crypto</span>
          </h1>
          <p className="mt-1 text-sm text-muted">
            Real prices. Simulated trades. Play money.
          </p>
        </div>

        <form onSubmit={submit} className="rounded-xl border border-hairline bg-surface p-6">
          <div className="mb-5 flex rounded-lg bg-canvas p-1">
            {(['login', 'register'] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => {
                  setMode(option)
                  setError(null)
                }}
                className={`flex-1 rounded-md py-1.5 text-sm font-medium transition ${
                  mode === option ? 'bg-raised text-ink' : 'text-muted hover:text-ink'
                }`}
              >
                {option === 'login' ? 'Log in' : 'Register'}
              </button>
            ))}
          </div>

          <label className="mb-1.5 block text-xs font-medium text-muted" htmlFor="email">
            Email
          </label>
          <input
            id="email"
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            className="mb-4 w-full rounded-lg border border-hairline bg-canvas px-3 py-2 text-sm outline-none placeholder:text-muted/60 focus:border-accent"
          />

          <label className="mb-1.5 block text-xs font-medium text-muted" htmlFor="password">
            Password
          </label>
          <div className="relative">
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              required
              // The browser is told which of the two this is, so a password manager offers to
              // save a new one rather than autofilling the old one over it.
              autoComplete={registering ? 'new-password' : 'current-password'}
              // Matches the backend's rule exactly. 8 is its minimum, and 72 is BCrypt's
              // ceiling, beyond which it stops reading. Enforcing it here means the user
              // finds out while typing rather than after a round trip.
              minLength={registering ? 8 : undefined}
              maxLength={registering ? 72 : undefined}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={registering ? 'At least 8 characters' : ''}
              className="w-full rounded-lg border border-hairline bg-canvas px-3 py-2 pr-10 text-sm outline-none placeholder:text-muted/60 focus:border-accent"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted hover:text-ink focus:outline-none"
              aria-label={showPassword ? 'Şifreyi gizle' : 'Şifreyi göster'}
              title={showPassword ? 'Şifreyi gizle' : 'Şifreyi göster'}
            >
              {showPassword ? (
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
              ) : (
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/><line x1="2" x2="22" y1="2" y2="22"/></svg>
              )}
            </button>
          </div>

          {registering && (
            <p className="mt-2 text-xs text-muted">
              You will get a random starting balance between $10,000 and $100,000 in play money.
            </p>
          )}

          {error && (
            <div className="mt-4">
              <ErrorNote message={error} />
            </div>
          )}

          <Button type="submit" disabled={busy} className="mt-5 w-full">
            {busy ? <Spinner label={registering ? 'Creating account' : 'Logging in'} /> : registering ? 'Create account' : 'Log in'}
          </Button>
        </form>
      </div>
    </div>
  )
}