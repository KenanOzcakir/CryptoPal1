package com.cryptopal.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of Binance's /api/v3/ticker/24hr reply.
 *
 * <p>This replaced the older /api/v3/ticker/price row, which carried nothing but a symbol
 * and a price. The 24 hour endpoint costs one extra call's worth of weight and returns the
 * change and the volume alongside the price, so the market table has something to say
 * beyond a number.
 *
 * <p>Everything arrives as a String, including the numbers, which is Binance being
 * careful rather than sloppy: a JSON number would be parsed as a double by most clients,
 * and 64000.00000001 does not survive that. They stay Strings until BigDecimal takes them.
 */
// Binance sends roughly twenty fields per row (bid, ask, trade counts, open time, and so
// on). Only these four are wanted, and ignoring the rest means Binance can add more
// without breaking this.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Binance24hrTicker(

        /** The pair, for example BTCUSDT. */
        String symbol,

        /** The most recent trade price. Same value the old /ticker/price endpoint returned. */
        String lastPrice,

        /** Change over 24 hours as a percentage, already worked out by Binance, for example "-1.156". */
        String priceChangePercent,

        /** Value traded over 24 hours in the quote asset, so USDT here rather than coins. */
        String quoteVolume) {
}
