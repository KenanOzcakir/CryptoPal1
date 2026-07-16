package com.cryptopal.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's virtual fiat balance. One per user, created at registration with a
 * randomized starting amount.
 *
 * <p>This lives in auth rather than trading because registration is what brings a wallet
 * into existence, so auth owns its lifecycle. Trading imports it to move money, but does
 * not create it.
 *
 * <p>The balance is a BigDecimal against a numeric(20,2) column. Money never touches a
 * double here: 0.1 + 0.2 is not 0.3 in binary floating point, and a balance that drifts
 * by fractions of a cent on every trade is a bug that is miserable to find later.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // A plain UUID rather than a @ManyToOne User. The relationship is already enforced
    // by the foreign key, and nothing here needs to navigate from a wallet back to its
    // user, so mapping the association would only add lazy loading to think about.
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "fiat_balance", nullable = false)
    private BigDecimal fiatBalance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Wallet() {
    }

    public Wallet(UUID userId, BigDecimal fiatBalance) {
        this.userId = userId;
        this.fiatBalance = fiatBalance;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getFiatBalance() {
        return fiatBalance;
    }

    public void setFiatBalance(BigDecimal fiatBalance) {
        this.fiatBalance = fiatBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
