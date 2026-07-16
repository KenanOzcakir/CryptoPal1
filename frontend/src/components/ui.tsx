import type { ReactNode } from 'react'
import { formatPercent } from '../lib/format'

/**
 * The small pieces the whole app reuses. Kept together because each is a handful of lines
 * and splitting them into six files would be filing, not structure.
 */

/**
 * A percentage, coloured and pointed.
 *
 * The triangle matters as much as the colour: roughly one man in twelve has some red/green
 * colour blindness, and to them a red number and a green number look identical. The arrow
 * says which way it went without needing the colour at all.
 */
export function Change({ value }: { value?: number }) {
  if (value === undefined || value === null) {
    // The ticker engine cannot produce a real 24 hour change, so the backend omits it.
    // A dash is honest; a green 0.00% would be a lie.
    return <span className="text-muted">-</span>
  }
  const up = value >= 0
  return (
    <span className={`tnum inline-flex items-center gap-1 ${up ? 'text-up' : 'text-down'}`}>
      <span aria-hidden="true" className="text-[0.6em] leading-none">
        {up ? '▲' : '▼'}
      </span>
      {formatPercent(Math.abs(value)).replace('+', '')}
    </span>
  )
}

const COIN_COLORS: Record<string, string> = {
  BTC: 'bg-[#f7931a]',
  ETH: 'bg-[#627eea]',
  SOL: 'bg-[#9945ff]',
  XRP: 'bg-[#23292f]',
}

/**
 * A coin badge.
 *
 * Real logos would mean shipping four trademarked images, so this is the first letter on
 * each coin's own brand colour. It reads as a coin at a glance, which is all the table
 * needs, and there is nothing to license or keep up to date.
 */
export function CoinIcon({ symbol, size = 28 }: { symbol: string; size?: number }) {
  const color = COIN_COLORS[symbol] ?? 'bg-raised'
  return (
    <span
      className={`${color} inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white`}
      style={{ width: size, height: size, fontSize: size * 0.42 }}
      aria-hidden="true"
    >
      {symbol.charAt(0)}
    </span>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-muted" role="status">
      <span className="size-4 animate-spin rounded-full border-2 border-hairline border-t-accent" />
      {label}
    </span>
  )
}

/**
 * A failure, said out loud.
 *
 * Requirement 6.3 asks for graceful failure and 6.6 for clear messages, so errors get a
 * panel of their own rather than a console log. The backend's messages are written to be
 * shown to a user, so they are shown as-is.
 */
export function ErrorNote({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className="flex items-center justify-between gap-4 rounded-lg border border-down/30 bg-down/10 px-4 py-3 text-sm"
    >
      <span className="text-down">{message}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded-md border border-down/40 px-2.5 py-1 text-xs text-down transition hover:bg-down/15"
        >
          Try again
        </button>
      )}
    </div>
  )
}

/** A rounded panel. The card shape from the top row of the CoinMarketCap layout. */
export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-xl border border-hairline bg-surface p-4 ${className}`}>{children}</div>
  )
}

export function Button({
  children,
  variant = 'primary',
  className = '',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'up' | 'down' }) {
  const styles = {
    primary: 'bg-accent hover:bg-accent-hover text-white',
    ghost: 'border border-hairline text-ink hover:bg-raised',
    up: 'bg-up hover:brightness-110 text-[#062b1d]',
    down: 'bg-down hover:brightness-110 text-white',
  }[variant]
  return (
    <button
      {...props}
      className={`rounded-lg px-3.5 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-40 ${styles} ${className}`}
    >
      {children}
    </button>
  )
}
