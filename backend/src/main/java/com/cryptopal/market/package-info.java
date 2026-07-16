/**
 * Where prices come from, and where they are kept.
 *
 * <p>{@code MarketDataProvider} is the seam between the application and the outside
 * world: Binance is the real source, and the local ticker engine is the fallback, so
 * the rest of the code never learns which one answered. Prices are refreshed every 15
 * seconds, cached in Redis for fast reads, and appended to PostgreSQL as snapshots for
 * history.
 *
 * <p>Binance is used strictly as a price feed. This application never places a real
 * order against it, and every trade is simulated locally.
 */
package com.cryptopal.market;
