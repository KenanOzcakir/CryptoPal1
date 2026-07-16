import { NavLink, Navigate, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { AskBar } from './features/ai-chat/AskBar'
import { AuthPage } from './features/auth/AuthPage'
import { MarketPage } from './features/market/MarketPage'
import { PortfolioPage } from './features/portfolio/PortfolioPage'
import { useAuth } from './state/AuthContext'

/**
 * The routes.
 *
 * Market is public, matching the backend, where /api/market is the one thing outside the
 * session filter. Everything else needs a login. Keeping the two in step means the UI
 * never offers a page the API would refuse.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginOnlyIfLoggedOut />} />
      <Route element={<Shell />}>
        <Route path="/" element={<MarketPage />} />
        <Route
          path="/portfolio"
          element={
            <RequireAuth>
              <PortfolioPage />
            </RequireAuth>
          }
        />
        {/* Anything unknown goes to the market page rather than a blank screen. */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

function LoginOnlyIfLoggedOut() {
  const { user } = useAuth()
  return user ? <Navigate to="/" replace /> : <AuthPage />
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  return user ? <>{children}</> : <Navigate to="/login" replace />
}

function Shell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-hairline bg-canvas/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex size-7 items-center justify-center rounded-lg bg-accent text-sm font-bold">
              C
            </span>
            <span className="font-semibold">CryptoPal</span>
          </div>

          <nav className="flex items-center gap-1 text-sm">
            <Tab to="/" label="Market" />
            {user && <Tab to="/portfolio" label="Portfolio" />}
          </nav>

          <div className="ml-auto flex items-center gap-3 text-sm">
            {user ? (
              <>
                <span className="hidden text-muted sm:inline">{user.email}</span>
                <button
                  onClick={async () => {
                    await logout()
                    navigate('/', { replace: true })
                  }}
                  className="rounded-lg border border-hairline px-3 py-1.5 text-sm transition hover:bg-raised"
                >
                  Log out
                </button>
              </>
            ) : (
              <button
                onClick={() => navigate('/login')}
                className="rounded-lg bg-accent px-3.5 py-1.5 text-sm font-semibold text-white transition hover:bg-accent-hover"
              >
                Log in
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Room at the bottom so the ask bar never covers the last row of a table. */}
      <main className={user ? 'pb-44' : 'pb-10'}>
        <Outlet />
      </main>

      {/* The assistant only knows about an account, so it is only offered when there is
          one. Showing it logged out would be a button that returns 401. */}
      {user && <AskBar />}
    </div>
  )
}

function Tab({ to, label }: { to: string; label: string }) {
  return (
    <NavLink
      to={to}
      end
      className={({ isActive }) =>
        `rounded-lg px-3 py-1.5 font-medium transition ${
          isActive ? 'text-ink' : 'text-muted hover:text-ink'
        }`
      }
    >
      {label}
    </NavLink>
  )
}
