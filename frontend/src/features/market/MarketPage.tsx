import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, CryptoPalError } from '../../api/client'
import type { PriceQuote } from '../../api/types'
import { Button, Change, CoinIcon, ErrorNote, Spinner } from '../../components/ui'
import { formatAgo, formatCompact, formatPrice } from '../../lib/format'
import { TradeModal } from '../trading/TradeModal'
import { useAuth } from '../../state/AuthContext'

const COIN_NAMES: Record<string, string> = {
  BTC: 'Bitcoin',
  ETH: 'Ethereum',
  SOL: 'Solana',
  XRP: 'XRP',
}

/**
 * The price table.
 *
 * Modelled on CoinMarketCap's, minus the columns an exchange cannot answer: no market cap
 * and no circulating supply, because Binance publishes neither, and no 1h or 7d change,
 * because its ticker only covers 24 hours. Inventing those from a second source would make
 * the table look complete and some of it would be guesswork.
 */
export function MarketPage() {
  const [tradingSymbol, setTradingSymbol] = useState<string | null>(null)
  const { user } = useAuth()

  const prices = useQuery({
    queryKey: ['prices'],
    queryFn: api.prices,
    // The server refreshes every 15 seconds, so polling faster only burns requests to
    // re-read a number that has not moved. Slightly under 15 keeps the display within a
    // tick of the cache without chasing it.
    refetchInterval: 15_000,
    // Keeps the previous rows on screen while the next poll is in flight, so the table
    // does not blink back to a spinner every 15 seconds.
    placeholderData: (previous) => previous,
  })

  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <header className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Market</h1>
          <p className="mt-1 text-sm text-muted">
            Live rates from Binance. Trades here are simulated and never reach an exchange.
          </p>
        </div>
        <div className="flex items-center gap-3 text-xs text-muted">
          {prices.isFetching && <Spinner label="Updating" />}
          {prices.data?.[0] && !prices.isFetching && (
            <span>Updated {formatAgo(prices.data[0].quotedAt)}</span>
          )}
          {/* Requirement 5.6.2 asks for a manual refresh alongside the automatic poll. */}
          <Button variant="ghost" onClick={() => prices.refetch()} disabled={prices.isFetching}>
            Refresh
          </Button>
        </div>
      </header>

      {prices.isError && (
        <ErrorNote
          message={
            prices.error instanceof CryptoPalError && prices.error.code === 'PRICE_UNAVAILABLE'
              ? 'Prices are not ready yet. The server refreshes them every 15 seconds.'
              : (prices.error as Error).message
          }
          onRetry={() => prices.refetch()}
        />
      )}

      {prices.isLoading && (
        <div className="rounded-xl border border-hairline bg-surface p-10 text-center">
          <Spinner label="Loading prices" />
        </div>
      )}

      {prices.data && (
        <div className="overflow-x-auto rounded-xl border border-hairline bg-surface">
          <table className="w-full min-w-[640px] text-sm">
            <thead>
              <tr className="border-b border-hairline text-xs text-muted">
                <th className="px-4 py-3 text-left font-medium">#</th>
                <th className="px-4 py-3 text-left font-medium">Name</th>
                <th className="px-4 py-3 text-right font-medium">Price</th>
                <th className="px-4 py-3 text-right font-medium">24h %</th>
                {/* Named honestly. This is Binance's own volume, not an all-exchange
                    figure, so it is far smaller than the number CoinMarketCap prints. */}
                <th className="px-4 py-3 text-right font-medium">Volume (24h, Binance)</th>
                <th className="px-4 py-3 text-right font-medium" />
              </tr>
            </thead>
            <tbody>
              {prices.data.map((quote, index) => (
                <Row
                  key={quote.symbol}
                  rank={index + 1}
                  quote={quote}
                  canTrade={Boolean(user)}
                  onTrade={() => setTradingSymbol(quote.symbol)}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="mt-3 text-xs text-muted">
        Market cap, circulating supply and 1h/7d changes are not shown because Binance is an
        exchange rather than a market data aggregator, and it does not publish them.
      </p>

      {tradingSymbol && (
        <TradeModal
          symbol={tradingSymbol}
          price={prices.data?.find((q) => q.symbol === tradingSymbol)?.price ?? 0}
          onClose={() => setTradingSymbol(null)}
        />
      )}
    </div>
  )
}

function Row({
  rank,
  quote,
  canTrade,
  onTrade,
}: {
  rank: number
  quote: PriceQuote
  canTrade: boolean
  onTrade: () => void
}) {
  return (
    <tr className="border-b border-hairline/60 transition last:border-0 hover:bg-raised/40">
      <td className="px-4 py-4 text-muted">{rank}</td>
      <td className="px-4 py-4">
        <div className="flex items-center gap-3">
          <CoinIcon symbol={quote.symbol} />
          <span className="font-medium">{COIN_NAMES[quote.symbol] ?? quote.symbol}</span>
          <span className="text-xs text-muted">{quote.symbol}</span>
        </div>
      </td>
      <td className="tnum px-4 py-4 text-right font-medium">{formatPrice(quote.price)}</td>
      <td className="px-4 py-4 text-right">
        <Change value={quote.changePercent24h} />
      </td>
      <td className="tnum px-4 py-4 text-right text-muted">
        {quote.volume24h ? formatCompact(quote.volume24h) : '-'}
      </td>
      <td className="px-4 py-4 text-right">
        {canTrade && (
          <Button variant="ghost" onClick={onTrade}>
            Trade
          </Button>
        )}
      </td>
    </tr>
  )
}
