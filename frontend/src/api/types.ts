/**
 * The shapes the backend actually returns.
 *
 * These mirror the Java records one for one. If one of them drifts, the fix is to look at
 * http://localhost:8080/v3/api-docs, which is generated from the code and so cannot be
 * out of date, unlike this file.
 */

/** Every failure from every endpoint arrives in this shape, including 404s and 405s. */
export interface ApiError {
  message: string
  code: ErrorCode
  timestamp: string
}

/**
 * Branch on these, never on `message`. The codes are a contract; the messages are English
 * prose the backend is free to reword.
 */
export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'DUPLICATE_EMAIL'
  | 'INSUFFICIENT_FUNDS'
  | 'INSUFFICIENT_HOLDINGS'
  | 'UNSUPPORTED_SYMBOL'
  | 'PRICE_UNAVAILABLE'
  | 'AI_UNAVAILABLE'
  | 'INTERNAL_ERROR'

export interface AuthUser {
  id: string
  email: string
}

export interface AuthResponse {
  token: string
  user: AuthUser
}

/**
 * One asset's latest price.
 *
 * `changePercent24h` and `volume24h` are optional on purpose: the backend omits them when
 * the price came from the offline ticker engine instead of Binance, so the UI has to cope
 * with their absence rather than print "undefined".
 *
 * `volume24h` is Binance-only, not a global figure, which is why it is labelled as such in
 * the table. It will look small next to an aggregator like CoinMarketCap.
 */
export interface PriceQuote {
  symbol: string
  price: number
  quotedAt: string
  changePercent24h?: number
  volume24h?: number
}

export type OrderSide = 'BUY' | 'SELL'

/**
 * An order.
 *
 * Read this twice: `amount` means different things depending on `side`. For a BUY it is
 * the fiat to spend; for a SELL it is the quantity of crypto to sell. The UI must never
 * let a user guess which one it wants.
 */
export interface OrderRequest {
  symbol: string
  side: OrderSide
  amount: number
}

/** What a completed order did. Carries the resulting balance and position, so the UI can
 *  update from this alone rather than making a second call that might land after another
 *  trade and show a number that never matched this one. */
export interface OrderResponse {
  transactionId: string
  symbol: string
  side: OrderSide
  quantity: number
  executionPrice: number
  fiatAmount: number
  fiatBalance: number
  holdingQuantity: number
  executedAt: string
}

export interface TransactionView {
  id: string
  symbol: string
  side: OrderSide
  quantity: number
  executionPrice: number
  fiatAmount: number
  createdAt: string
}

export interface HoldingView {
  symbol: string
  quantity: number
  price: number
  estimatedValue: number
}

/**
 * Everything the user owns, priced now.
 *
 * Everything except `fiatBalance` is an estimate: the balance is stored, the rest is that
 * balance multiplied by a price that has already moved. Positions sold to zero are not
 * included.
 */
export interface Portfolio {
  fiatBalance: number
  holdings: HoldingView[]
  holdingsValue: number
  totalValue: number
  recentTransactions: TransactionView[]
  valuedAt: string
}

export interface AiInsightRequest {
  question: string
}

/** The answer, as Markdown. Render it through react-markdown, never as raw HTML. */
export interface AiInsightResponse {
  answer: string
}
