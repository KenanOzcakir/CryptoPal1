# API Contract

This file defines the backend and frontend contract for CryptoPal. Keep it updated when request or response shapes change.

## Authentication

### Register

```text
POST /api/auth/register
```

Request:

```json
{
  "email": "user@example.com",
  "password": "StrongPassword123"
}
```

Response:

```json
{
  "token": "session-token",
  "user": {
    "id": "uuid",
    "email": "user@example.com"
  }
}
```

### Login

```text
POST /api/auth/login
```

Request:

```json
{
  "email": "user@example.com",
  "password": "StrongPassword123"
}
```

Response uses the same shape as register.

### Logout

```text
POST /api/auth/logout
```

Requires the auth header. Returns `204 No Content` and deletes the session from Redis, so
the token stops working immediately rather than lingering until it would have expired.
Logging out twice is harmless.

### Auth Header

Protected endpoints use this header:

```text
Authorization: Bearer <session-token>
```

The token is opaque: 32 random bytes, Base64url encoded to 43 characters. It carries no
information and only means anything as a key into Redis.

Public endpoints (no header needed): `POST /api/auth/register`, `POST /api/auth/login`,
and everything under `/api/market`. Everything else under `/api/` requires a session and
is refused with `UNAUTHORIZED` otherwise. An unknown path under `/api/` also answers
`UNAUTHORIZED` rather than `NOT_FOUND`, because the filter decides before the router
does, which keeps the API surface from being mapped by probing.

## Market

Public. No auth header needed.

### Latest Prices

```text
GET /api/market/prices
```

Response, one entry per supported asset in configured order:

```json
[
  {
    "symbol": "BTC",
    "price": 64439.55,
    "quotedAt": "2026-07-16T12:00:00Z",
    "changePercent24h": -1.105,
    "volume24h": 1325675332.11
  }
]
```

`changePercent24h` and `volume24h` are **display only**. Nothing that moves money reads
them: trading, portfolio valuation, and the AI context all use `price` alone.

Both are **omitted from the JSON when absent**, so treat them as optional. They are absent
when the price came from the local ticker engine rather than a real exchange (the engine
supplies a drift figure in place of a change, and never a volume, because an invented
trading volume would mean nothing).

`volume24h` is the value traded **on Binance only**, in USDT. It is not a global figure,
so it is smaller than the number an aggregator like CoinMarketCap shows for the same
asset. Label it accordingly in any UI.

Not available from this API, because Binance is an exchange rather than a market data
aggregator: market cap, circulating supply, and 1 hour or 7 day changes.

Served from Redis, refreshed every 15 seconds from the Binance Spot API. Prices are real
exchange rates, but no order placed through this application ever reaches Binance. If
Binance is unreachable the values come from the local ticker engine instead and are
simulated.

Returns `PRICE_UNAVAILABLE` (503) when nothing is cached yet, which means the app has only
just started or every refresh has failed for a minute.

### Latest Price For One Asset

```text
GET /api/market/prices/{symbol}
```

Symbol is case insensitive, so `BTC` and `btc` both work. Returns a single object in the
shape above.

Errors: `UNSUPPORTED_SYMBOL` (400) for an asset that is not traded here, which will never
work; `PRICE_UNAVAILABLE` (503) when there is no cached price yet, which fixes itself
within seconds.

## Orders

### Execute Order

```text
POST /api/orders
```

Request:

```json
{
  "symbol": "BTC",
  "side": "BUY",
  "amount": 100.00
}
```

`symbol` and `side` are both case insensitive, so `"btc"` and `"buy"` are accepted.

Important amount rule:

- BUY `amount` means fiat amount to spend.
- SELL `amount` means crypto quantity to sell.

Response:

```json
{
  "transactionId": "uuid",
  "symbol": "BTC",
  "side": "BUY",
  "quantity": 0.01563232,
  "executionPrice": 63970.00000000,
  "fiatAmount": 1000.00,
  "fiatBalance": 83401.00,
  "holdingQuantity": 0.01563232,
  "executedAt": "2026-07-16T12:58:39Z"
}
```

`fiatBalance` and `holdingQuantity` are the values after this order, so the UI can update
itself without a follow-up call to `/api/portfolio` that might land after another trade.

For a BUY, `quantity` is `fiatAmount / executionPrice` rounded **down** to 8 decimals, so
a rounding error can never hand out more coin than was paid for.

Errors: `INSUFFICIENT_FUNDS`, `INSUFFICIENT_HOLDINGS`, `UNSUPPORTED_SYMBOL`,
`PRICE_UNAVAILABLE`, and `VALIDATION_ERROR` (which also covers amounts that are positive
but round away to nothing, such as spending less than a cent).

## Portfolio

```text
GET /api/portfolio
```

Response:

```json
{
  "fiatBalance": 83401.00,
  "holdings": [
    { "symbol": "BTC", "quantity": 0.01563232, "price": 63970.00000000, "estimatedValue": 1000.00 }
  ],
  "holdingsValue": 1000.00,
  "totalValue": 84401.00,
  "recentTransactions": [],
  "valuedAt": "2026-07-16T12:58:50Z"
}
```

`fiatBalance + holdingsValue == totalValue`. Everything except `fiatBalance` is an
estimate: the balance is stored, the rest is that balance multiplied by a price that has
already moved. Positions sold down to zero are omitted. Returns `PRICE_UNAVAILABLE` (503)
if a held asset has no cached price, rather than valuing it at nothing.

## Transactions

```text
GET /api/transactions
```

Returns the 50 most recent trades, newest first, each in the shape used by
`recentTransactions` above.

## AI

```text
POST /api/ai/ask
```

Requires the auth header.

Request:

```json
{
  "question": "What is my current portfolio worth?"
}
```

`question` must not be blank and is capped at 500 characters.

Response:

```json
{
  "answer": "Your total account value is **84,401.03 virtual USD** ..."
}
```

`answer` is **Markdown**, so the frontend renders it through a Markdown component rather
than injecting it as HTML.

Gemini is called only from the backend and is given only this account's own balance,
holdings, recent trades, current prices, and a precomputed recent-movement figure per
asset. It is instructed to use nothing else, never to invent figures, never to recompute
the numbers it is given, and never to give guaranteed financial advice.

Errors: `VALIDATION_ERROR` (400, blank or over 500 characters), `UNAUTHORIZED` (401),
`RATE_LIMITED` (429, see below), `AI_UNAVAILABLE` (503, assistant unreachable, busy, or not
configured on the server), `PRICE_UNAVAILABLE` (503, no cached prices to build the context
from).

The app runs without a Gemini key. Only this endpoint degrades, answering
`AI_UNAVAILABLE`.

### How many questions you get

This is the only endpoint with a quota, because it is the only one that spends someone
else's capacity. Two limits apply, and crossing either answers `RATE_LIMITED` (429):

| Limit | Default | What it is for |
|---|---|---|
| Per user | 20 questions | Fairness. One person cannot lean on the button all afternoon |
| Everyone together | 300 questions | The real protection. Registration is open, so a per-user limit alone would mean ten accounts get ten times the allowance |

Both are rolling 24 hour windows that start at the first question, not calendar days, so
there is no particular midnight to wait for. The message says which limit was hit and when
it resets. Both are configurable (`AI_DAILY_QUESTION_LIMIT`, `AI_DAILY_GLOBAL_LIMIT`).

The **attempt** is counted, not the answer. A question that Gemini fails to answer still
spends one, because counting only successes would make failures free and endlessly
retryable, which is precisely how a quota gets drained.

Nothing else in the app is rate limited, and nothing else stops working when this runs out.

## Standard Error Response

```json
{
  "message": "Insufficient funds to complete this trade",
  "code": "INSUFFICIENT_FUNDS",
  "timestamp": "2026-07-16T12:00:00Z"
}
```

Error codes and the status each one returns:

```text
VALIDATION_ERROR       400
UNAUTHORIZED           401
NOT_FOUND              404
METHOD_NOT_ALLOWED     405
DUPLICATE_EMAIL        409
INSUFFICIENT_FUNDS     400
INSUFFICIENT_HOLDINGS  400
UNSUPPORTED_SYMBOL     400
RATE_LIMITED           429
PRICE_UNAVAILABLE      503
AI_UNAVAILABLE         503
INTERNAL_ERROR         500
```

`PRICE_UNAVAILABLE` and `AI_UNAVAILABLE` are 503 rather than 500 because nothing is
broken in either case: the price refreshes within 15 seconds, so retrying is the right
response.

Every endpoint returns this shape on failure, including the ones the framework answers
itself, so the frontend only ever has one error format to handle.
