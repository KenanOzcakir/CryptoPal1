package com.cryptopal.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trade that happened. Written inside the same transaction that moved the money, so the
 * log and the balances can never disagree: either both landed or neither did.
 *
 * <p>Only successful trades are recorded. A rejected order changed nothing, so there is
 * nothing to record about it.
 *
 * <p>Rows here are never updated or deleted. This is the audit trail, and the portfolio is
 * only true if this is.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String symbol;

    // EnumType.STRING is not optional here. JPA's default is ORDINAL, which would store
    // BUY as 0 and SELL as 1, and the column is varchar(4) with a
    // CHECK (side in ('BUY','SELL')). Every insert would be rejected by the database.
    // Ordinal is also fragile on its own terms: reordering the enum would silently
    // rewrite the meaning of every row already stored.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    /** How much crypto moved. Always positive: side says which way. */
    @Column(nullable = false)
    private BigDecimal quantity;

    /** The price used, numeric(20,8), because a rate is not an amount. */
    @Column(name = "execution_price", nullable = false)
    private BigDecimal executionPrice;

    /** How much virtual fiat moved, numeric(20,2). */
    @Column(name = "fiat_amount", nullable = false)
    private BigDecimal fiatAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Transaction() {
    }

    public Transaction(UUID userId, String symbol, OrderSide side,
                       BigDecimal quantity, BigDecimal executionPrice, BigDecimal fiatAmount) {
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.executionPrice = executionPrice;
        this.fiatAmount = fiatAmount;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getExecutionPrice() {
        return executionPrice;
    }

    public BigDecimal getFiatAmount() {
        return fiatAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
