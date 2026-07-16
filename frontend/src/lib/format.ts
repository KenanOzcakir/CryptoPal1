/**
 * How numbers are shown.
 *
 * Kept in one place because a price formatted two different ways on two screens is the
 * kind of thing that makes people distrust a finance app, even when the number is right.
 */

/**
 * A crypto price.
 *
 * The decimals scale with the size, because one rule cannot serve BTC at 64,386 and XRP at
 * 1.1085 at once. Two decimals on XRP would round away a real difference; four on SOL
 * gives "76.1700", which is two zeros of noise. The cut is at 10, which keeps BTC, ETH and
 * SOL at cents and leaves XRP the precision it actually needs.
 *
 * This is the display-side echo of why the database stores prices at 8 decimals rather
 * than 2: a price is a rate, and the small ones carry meaning in their tail.
 */
export function formatPrice(value: number): string {
  const decimals = value >= 10 ? 2 : value >= 1 ? 4 : 6
  return `$${value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`
}

/** A fiat amount. Always cents, because that is what the wallet column stores. */
export function formatUsd(value: number): string {
  return `$${value.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

/** A crypto quantity, at the 8 decimals the backend keeps, with trailing zeros trimmed. */
export function formatQuantity(value: number): string {
  return value
    .toLocaleString('en-US', { minimumFractionDigits: 8, maximumFractionDigits: 8 })
    .replace(/\.?0+$/, '')
}

/** A percentage with a fixed sign, so "+" and "-" both read as deliberate. */
export function formatPercent(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

/**
 * A large figure shortened, for the volume column.
 *
 * Volume is context, not an amount anyone acts on, so 1.33B is more useful at a glance
 * than 1,325,675,332.
 */
export function formatCompact(value: number): string {
  return `$${value.toLocaleString('en-US', {
    notation: 'compact',
    maximumFractionDigits: 2,
  })}`
}

/** "just now", "12s ago". Used for the last-updated line the market page requires. */
export function formatAgo(iso: string): string {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (seconds < 5) return 'just now'
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  return `${Math.round(minutes / 60)}h ago`
}

/** A timestamp for the trade history, in the reader's own locale and zone. */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
