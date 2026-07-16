import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api, CryptoPalError } from '../../api/client'
import type { OrderResponse, OrderSide } from '../../api/types'
import { Button, CoinIcon, ErrorNote, Spinner } from '../../components/ui'
import { formatPrice, formatQuantity, formatUsd } from '../../lib/format'

/**
 * Buy and sell.
 *
 * The whole design of this modal is aimed at one problem. The API takes a single `amount`
 * that means fiat on a BUY and coin on a SELL, which is the documented shape and the
 * easiest thing in the project to get wrong: sending 1000 to a SELL asks to sell a
 * thousand whole Bitcoin, and it fails with a confusing error rather than an explanation.
 *
 * So the field is never just "amount". It is labelled by what it is, prefixed or suffixed
 * with the unit, and above all it shows live what the trade will actually do. Someone
 * about to make that mistake sees "You will sell 1000 BTC" before they ever press the
 * button.
 */
export function TradeModal({
  symbol,
  price,
  onClose,
}: {
  symbol: string
  price: number
  onClose: () => void
}) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [amount, setAmount] = useState('')
  const [done, setDone] = useState<OrderResponse | null>(null)
  const queryClient = useQueryClient()

  // Needed to honour requirement 5.6.3: offer Buy only with the funds for it, and Sell
  // only when the asset is actually held.
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio })

  const balance = portfolio.data?.fiatBalance ?? 0
  const held = portfolio.data?.holdings.find((h) => h.symbol === symbol)?.quantity ?? 0
  const parsed = Number(amount)
  const valid = amount !== '' && Number.isFinite(parsed) && parsed > 0

  const order = useMutation({
    mutationFn: () => api.order({ symbol, side, amount: parsed }),
    onSuccess: (response) => {
      setDone(response)
      // The trade changed the wallet and the positions, so anything showing them is now
      // stale. Invalidating is simpler and safer than patching each cache by hand.
      void queryClient.invalidateQueries({ queryKey: ['portfolio'] })
      void queryClient.invalidateQueries({ queryKey: ['transactions'] })
    },
  })

  // Escape closes it, which is the one keyboard habit people actually have with dialogs.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const buying = side === 'BUY'
  const canAfford = !buying || parsed <= balance
  const canDeliver = buying || parsed <= held
  const submittable = valid && canAfford && canDeliver && !order.isPending

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4"
      onClick={onClose}
      role="presentation"
    >
      <div
        className="w-full max-w-md rounded-xl border border-hairline bg-surface p-5"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`Trade ${symbol}`}
      >
        <div className="mb-5 flex items-center gap-3">
          <CoinIcon symbol={symbol} size={32} />
          <div className="flex-1">
            <div className="font-semibold">Trade {symbol}</div>
            <div className="tnum text-xs text-muted">{formatPrice(price)} per coin</div>
          </div>
          <button onClick={onClose} className="text-muted hover:text-ink" aria-label="Close">
            ✕
          </button>
        </div>

        {done ? (
          <Receipt response={done} onClose={onClose} />
        ) : (
          <>
            <div className="mb-4 flex rounded-lg bg-canvas p-1">
              {(['BUY', 'SELL'] as const).map((option) => (
                <button
                  key={option}
                  type="button"
                  onClick={() => {
                    setSide(option)
                    // Cleared on purpose. The number means something completely different
                    // on the other tab, so carrying "1000" from Buy over to Sell would be
                    // handing someone the mistake.
                    setAmount('')
                    order.reset()
                  }}
                  className={`flex-1 rounded-md py-1.5 text-sm font-semibold transition ${
                    side === option
                      ? option === 'BUY'
                        ? 'bg-up/15 text-up'
                        : 'bg-down/15 text-down'
                      : 'text-muted hover:text-ink'
                  }`}
                >
                  {option === 'BUY' ? 'Buy' : 'Sell'}
                </button>
              ))}
            </div>

            <div className="mb-1.5 flex items-baseline justify-between">
              <label htmlFor="amount" className="text-xs font-medium text-muted">
                {buying ? 'Amount to spend' : `Quantity to sell`}
              </label>
              <span className="tnum text-xs text-muted">
                {buying ? `Balance ${formatUsd(balance)}` : `You hold ${formatQuantity(held)} ${symbol}`}
              </span>
            </div>

            <div className="relative">
              {/* The unit sits inside the field, so the number can never be read without
                  it. This is the difference between "1000" and "$1000". */}
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted">
                {buying ? '$' : ''}
              </span>
              <input
                id="amount"
                type="number"
                inputMode="decimal"
                min="0"
                step={buying ? '0.01' : '0.00000001'}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder={buying ? '100.00' : '0.00100000'}
                className={`tnum w-full rounded-lg border border-hairline bg-canvas py-2 text-sm outline-none placeholder:text-muted/50 focus:border-accent ${
                  buying ? 'pl-7 pr-3' : 'pl-3 pr-14'
                }`}
              />
              {!buying && (
                <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted">
                  {symbol}
                </span>
              )}
            </div>

            <button
              type="button"
              onClick={() => setAmount(buying ? String(balance) : String(held))}
              className="mt-2 text-xs text-accent hover:underline"
            >
              Use max
            </button>

            {/* The heart of it. Says in words what the trade will do, before it does it. */}
            <div className="mt-4 rounded-lg border border-hairline bg-canvas px-3 py-2.5 text-sm">
              {valid && price > 0 ? (
                buying ? (
                  <span>
                    Spend <strong className="tnum text-ink">{formatUsd(parsed)}</strong> and receive
                    about{' '}
                    <strong className="tnum text-up">
                      {formatQuantity(Math.floor((parsed / price) * 1e8) / 1e8)} {symbol}
                    </strong>
                  </span>
                ) : (
                  <span>
                    Sell{' '}
                    <strong className="tnum text-down">
                      {formatQuantity(parsed)} {symbol}
                    </strong>{' '}
                    and receive about <strong className="tnum text-ink">{formatUsd(parsed * price)}</strong>
                  </span>
                )
              ) : (
                <span className="text-muted">
                  {buying
                    ? 'Enter how many dollars to spend.'
                    : `Enter how many ${symbol} to sell.`}
                </span>
              )}
            </div>

            {valid && !canAfford && (
              <p className="mt-2 text-xs text-down">
                That is more than your balance of {formatUsd(balance)}.
              </p>
            )}
            {valid && !canDeliver && (
              <p className="mt-2 text-xs text-down">
                You only hold {formatQuantity(held)} {symbol}.
              </p>
            )}

            {order.isError && (
              <div className="mt-4">
                <ErrorNote
                  message={
                    order.error instanceof CryptoPalError
                      ? order.error.message
                      : 'The order could not be placed.'
                  }
                />
              </div>
            )}

            <Button
              variant={buying ? 'up' : 'down'}
              disabled={!submittable}
              onClick={() => order.mutate()}
              className="mt-5 w-full"
            >
              {order.isPending ? (
                <Spinner label="Placing order" />
              ) : (
                `${buying ? 'Buy' : 'Sell'} ${symbol}`
              )}
            </Button>
          </>
        )}
      </div>
    </div>
  )
}

/** What actually happened, in the same units the order was placed in. */
function Receipt({ response, onClose }: { response: OrderResponse; onClose: () => void }) {
  const buying = response.side === 'BUY'
  return (
    <div>
      <div
        className={`mb-4 rounded-lg px-3 py-2.5 text-sm ${buying ? 'bg-up/10 text-up' : 'bg-down/10 text-down'}`}
      >
        {buying ? 'Bought' : 'Sold'} {formatQuantity(response.quantity)} {response.symbol}
      </div>
      <dl className="space-y-2 text-sm">
        <Line label="Filled at" value={formatPrice(response.executionPrice)} />
        <Line label={buying ? 'Spent' : 'Received'} value={formatUsd(response.fiatAmount)} />
        <Line label="New balance" value={formatUsd(response.fiatBalance)} />
        <Line
          label={`${response.symbol} held`}
          value={formatQuantity(response.holdingQuantity)}
        />
      </dl>
      <Button onClick={onClose} className="mt-5 w-full">
        Done
      </Button>
    </div>
  )
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className="tnum">{value}</dd>
    </div>
  )
}
