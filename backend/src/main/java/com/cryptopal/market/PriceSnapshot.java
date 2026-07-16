package com.cryptopal.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One price, recorded permanently. A row lands here for every asset on every refresh, so
 * these accumulate into the price history the AI module reads to answer questions like
 * "how has BTC moved recently".
 *
 * <p>This is the durable half of the market data. The Redis copy is only ever the latest
 * value and is expected to disappear; this table is the one that remembers.
 *
 * <p>The price column is numeric(20,8), matching the rate precision used everywhere else,
 * rather than the 2 decimals fiat amounts use.
 */
@Entity
@Table(name = "price_snapshots")
public class PriceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal price;

    // Set from the quote rather than defaulted by the database, so the row records when the
    // price was actually quoted, not when the insert happened to run.
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** Required by JPA. */
    protected PriceSnapshot() {
    }

    public PriceSnapshot(String symbol, BigDecimal price, Instant capturedAt) {
        this.symbol = symbol;
        this.price = price;
        this.capturedAt = capturedAt;
    }

    /** Builds the durable row from a runtime quote. */
    public static PriceSnapshot from(PriceQuote quote) {
        return new PriceSnapshot(quote.symbol(), quote.price(), quote.quotedAt());
    }

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
