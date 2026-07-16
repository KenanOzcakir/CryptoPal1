package com.cryptopal.trading;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes the trade log. */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * A user's most recent trades, newest first.
     *
     * <p>Always bounded. This table only ever grows, and both the history page and the AI
     * context want "recent activity" rather than everything since registration. The
     * {@code (user_id, created_at desc)} index means Postgres reads these off the front
     * instead of sorting.
     */
    List<Transaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);
}
