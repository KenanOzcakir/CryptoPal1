package com.cryptopal.market;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes the price history. */
public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, UUID> {

    /**
     * The most recent prices for one asset, newest first.
     *
     * <p>Always bounded by a limit: the AI module wants a sense of recent movement, and
     * this table grows by four rows every fifteen seconds, so an unbounded read would get
     * slower every day the application stays up. The
     * {@code (symbol, captured_at desc)} index means Postgres reads these straight off the
     * front instead of sorting the table.
     */
    List<PriceSnapshot> findBySymbolOrderByCapturedAtDesc(String symbol, Limit limit);
}
