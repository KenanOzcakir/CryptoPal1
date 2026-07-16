import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.tsx'
import { CryptoPalError } from './api/client'
import './index.css'
import { AuthProvider } from './state/AuthContext'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The server refreshes prices every 15 seconds, so anything younger than that is
      // still the newest there is. Refetching sooner would ask for a number that cannot
      // have changed.
      staleTime: 10_000,
      // Refetching on every window focus is noise for a page that already polls.
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // Retrying a 401 or a validation error just asks the same question and gets the
        // same answer. Only the temporary failures are worth another go, and
        // PRICE_UNAVAILABLE is the honest one: it clears within a refresh cycle.
        if (error instanceof CryptoPalError) {
          const worthRetrying = error.code === 'PRICE_UNAVAILABLE' || error.code === 'INTERNAL_ERROR'
          return worthRetrying && failureCount < 2
        }
        return failureCount < 2
      },
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
