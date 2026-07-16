package com.cryptopal.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes wallets. Trading uses this too, which is why the repository is public
 * even though auth is what creates the rows.
 */
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    // Returns Optional even though the schema guarantees one wallet per user, because
    // the guarantee is that there is never more than one, not that there is always one.
    Optional<Wallet> findByUserId(UUID userId);

    /**
     * The same lookup, but taking a write lock on the row: this issues
     * {@code SELECT ... FOR UPDATE}, so a second transaction asking for the same wallet
     * waits here until the first one commits.
     *
     * <p>Every order must read the wallet through this, never through
     * {@link #findByUserId}. {@code @Transactional} on its own does not prevent a lost
     * update: under this database's default READ COMMITTED isolation, two concurrent
     * orders can both read a balance of 1000, both decide 100 is affordable, and both
     * write 900. One order's money simply vanishes, and no error is raised anywhere. The
     * lock is what serializes them, so the second reads 900 and decides against the truth.
     *
     * <p>Locking the wallet is also what protects the holdings: since every order for a
     * wallet queues on this one row, no two writers are ever inside the same wallet's
     * positions at once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
}
