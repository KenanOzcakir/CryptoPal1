import { useQuery } from '@tanstack/react-query'
import { api, CryptoPalError } from '../../api/client'
import { Card, CoinIcon, ErrorNote, Spinner } from '../../components/ui'
import { formatDateTime, formatPrice, formatQuantity, formatUsd } from '../../lib/format'

/**
 * The wallet, the positions, and the trade history.
 *
 * The stat cards along the top are the shape from the CoinMarketCap layout, filled with
 * the only figures this app can honestly produce: what the account holds, not what the
 * whole market is doing.
 */
export function PortfolioPage() {
  const portfolio = useQuery({
    queryKey: ['portfolio'],
    queryFn: api.portfolio,
    // Positions are valued at live prices, so this goes stale on the same 15 second
    // rhythm as the market page.
    refetchInterval: 15_000,
    placeholderData: (previous) => previous,
  })

  const transactions = useQuery({ queryKey: ['transactions'], queryFn: api.transactions })

  if (portfolio.isLoading) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-10 text-center">
        <Spinner label="Loading your portfolio" />
      </div>
    )
  }

  if (portfolio.isError) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-6">
        <ErrorNote
          message={
            portfolio.error instanceof CryptoPalError &&
            portfolio.error.code === 'PRICE_UNAVAILABLE'
              ? 'Your portfolio cannot be valued until prices are available. This usually clears within 15 seconds.'
              : (portfolio.error as Error).message
          }
          onRetry={() => portfolio.refetch()}
        />
      </div>
    )
  }

  const data = portfolio.data!
  // What the account is worth against what it started as is not something the backend
  // tracks, so it is not shown. A made up "profit" would be the easiest lie on the page.

  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <h1 className="mb-5 text-xl font-semibold">Portfolio</h1>

      <div className="mb-6 grid gap-3 sm:grid-cols-3">
        <Stat label="Total value" value={formatUsd(data.totalValue)} hint="Cash plus positions" />
        <Stat label="Cash" value={formatUsd(data.fiatBalance)} hint="Available to spend" />
        <Stat
          label="In crypto"
          value={formatUsd(data.holdingsValue)}
          hint={`${data.holdings.length} position${data.holdings.length === 1 ? '' : 's'}`}
        />
      </div>

      <section className="mb-6">
        <h2 className="mb-3 text-sm font-semibold text-muted">Holdings</h2>
        {data.holdings.length === 0 ? (
          <Card className="text-center text-sm text-muted">
            You do not hold any crypto yet. Buy something from the Market page.
          </Card>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-hairline bg-surface">
            <table className="w-full min-w-[520px] text-sm">
              <thead>
                <tr className="border-b border-hairline text-xs text-muted">
                  <th className="px-4 py-3 text-left font-medium">Asset</th>
                  <th className="px-4 py-3 text-right font-medium">Quantity</th>
                  <th className="px-4 py-3 text-right font-medium">Price</th>
                  <th className="px-4 py-3 text-right font-medium">Value</th>
                </tr>
              </thead>
              <tbody>
                {data.holdings.map((holding) => (
                  <tr key={holding.symbol} className="border-b border-hairline/60 last:border-0">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2.5">
                        <CoinIcon symbol={holding.symbol} size={24} />
                        <span className="font-medium">{holding.symbol}</span>
                      </div>
                    </td>
                    <td className="tnum px-4 py-3 text-right">{formatQuantity(holding.quantity)}</td>
                    <td className="tnum px-4 py-3 text-right text-muted">
                      {formatPrice(holding.price)}
                    </td>
                    <td className="tnum px-4 py-3 text-right font-medium">
                      {formatUsd(holding.estimatedValue)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="mt-2 text-xs text-muted">
          Every figure except cash is an estimate at the latest price, so it moves with the
          market.
        </p>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold text-muted">Trade history</h2>
        {transactions.isLoading && <Spinner label="Loading trades" />}
        {transactions.data?.length === 0 && (
          <Card className="text-center text-sm text-muted">No trades yet.</Card>
        )}
        {transactions.data && transactions.data.length > 0 && (
          <div className="overflow-x-auto rounded-xl border border-hairline bg-surface">
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="border-b border-hairline text-xs text-muted">
                  <th className="px-4 py-3 text-left font-medium">When</th>
                  <th className="px-4 py-3 text-left font-medium">Side</th>
                  <th className="px-4 py-3 text-left font-medium">Asset</th>
                  <th className="px-4 py-3 text-right font-medium">Quantity</th>
                  <th className="px-4 py-3 text-right font-medium">Price</th>
                  <th className="px-4 py-3 text-right font-medium">Total</th>
                </tr>
              </thead>
              <tbody>
                {transactions.data.map((trade) => (
                  <tr key={trade.id} className="border-b border-hairline/60 last:border-0">
                    <td className="px-4 py-3 text-muted">{formatDateTime(trade.createdAt)}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`rounded px-1.5 py-0.5 text-xs font-semibold ${
                          trade.side === 'BUY' ? 'bg-up/15 text-up' : 'bg-down/15 text-down'
                        }`}
                      >
                        {trade.side}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-medium">{trade.symbol}</td>
                    <td className="tnum px-4 py-3 text-right">{formatQuantity(trade.quantity)}</td>
                    <td className="tnum px-4 py-3 text-right text-muted">
                      {formatPrice(trade.executionPrice)}
                    </td>
                    <td className="tnum px-4 py-3 text-right">{formatUsd(trade.fiatAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <Card>
      <div className="text-xs text-muted">{label}</div>
      <div className="tnum mt-1 text-2xl font-semibold">{value}</div>
      <div className="mt-1 text-xs text-muted">{hint}</div>
    </Card>
  )
}
