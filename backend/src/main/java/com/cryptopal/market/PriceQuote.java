package com.cryptopal.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * PriceQuote is one asset's latest price at a moment in time. I use it all over the market
 * layer -> the providers hand it back, it gets cached in Redis, and later I turn it into a
 * PriceSnapshot row for history. I made it a record because it's just immutable data, and I
 * validate in the constructor so a bad price can never sneak in and cause weird numbers
 * downstream.
 */
public record PriceQuote(String symbol, BigDecimal price, Instant quotedAt) {

    public PriceQuote {
        // I fail fast on nulls so I get a clear error right where it's created, not later.
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quotedAt, "quotedAt");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        // A price of zero or below makes no sense for a real asset, so I reject it.
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive, was: " + price);
        }
    }
}
