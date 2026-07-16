package com.cryptopal.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes wallets. Trading uses this too, which is why the repository is public
 * even though auth is what creates the rows.
 */
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    // Returns Optional even though the schema guarantees one wallet per user, because
    // the guarantee is that there is never more than one, not that there is always one.
    Optional<Wallet> findByUserId(UUID userId);
}
