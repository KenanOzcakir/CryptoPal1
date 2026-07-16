package com.cryptopal.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * PriceQuote is one asset's latest price at a moment in time. I use it all over the market
 * layer -> the providers hand it back, it gets cached in Redis, and later I turn it into a
 * PriceSnapshot row for history. I made it a record because it's just immutable data, and I
 * validate in the constructor so a bad price can never sneak in and cause weird numbers
 * downstream.
 *
 * <p>The 24 hour figures came later, to give the market table something to colour green or
 * red. They are display only: nothing that moves money reads them, and everything that does
 * (trading, portfolio valuation, the AI context) still uses price alone. They are also
 * nullable, because the ticker engine cannot know a real volume and no snapshot ever
 * stores them, so anything reading them has to cope with their absence.
 */
// Nullable fields are dropped from the JSON rather than sent as nulls, so a quote with no
// 24 hour data looks like the original three field shape on the wire.
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One asset's latest price, with 24 hour figures where the source has them.")
public record PriceQuote(

        @Schema(example = "BTC") String symbol,
        @Schema(example = "64000.00000000") BigDecimal price,
        Instant quotedAt,

        @Schema(description = "Change over the last 24 hours, as a percentage. Negative means down. "
                + "Absent when the price came from the offline ticker engine's own baseline "
                + "rather than a real exchange.", example = "-1.156")
        BigDecimal changePercent24h,

        @Schema(description = "Value traded on the exchange in the last 24 hours, in USDT. "
                + "Absent when the price did not come from a real exchange.",
                example = "1378687174.87")
        BigDecimal volume24h) {

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
        // No check on the 24 hour figures beyond letting them be absent. A change of zero
        // is real, a negative change is real, and a volume of zero is real for a quiet
        // market, so there is nothing here that would be a bug.
    }

    /**
     * A quote with no 24 hour figures.
     *
     * <p>This exists so adding those fields did not ripple through every caller that only
     * ever cared about a price: the trading tests, the AI context, and the ticker engine
     * all still build a quote the short way.
     */
    public PriceQuote(String symbol, BigDecimal price, Instant quotedAt) {
        this(symbol, price, quotedAt, null, null);
    }
}
