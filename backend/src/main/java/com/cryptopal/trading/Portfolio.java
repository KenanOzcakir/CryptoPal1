package com.cryptopal.trading;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything a user owns, priced at this moment.
 *
 * <p>Every value here except {@code fiatBalance} is an estimate, and deliberately named
 * that way. The balance is a fact stored in PostgreSQL; the rest is that fact multiplied
 * by a price that will have moved by the time anyone reads it.
 */
@Schema(description = "A user's wallet, positions, and recent activity, valued at the latest prices.")
public record Portfolio(

        @Schema(description = "Virtual USD available to spend. Stored, not estimated.",
                example = "49900.00")
        BigDecimal fiatBalance,

        @Schema(description = "Positions with a non-zero quantity.")
        List<HoldingView> holdings,

        @Schema(description = "What the positions are worth at the latest prices.", example = "100.00")
        BigDecimal holdingsValue,

        @Schema(description = "fiatBalance + holdingsValue.", example = "50000.00")
        BigDecimal totalValue,

        @Schema(description = "The most recent trades, newest first.")
        List<TransactionView> recentTransactions,

        @Schema(description = "When this was valued.")
        Instant valuedAt) {

    /** One position, with the price used to value it. */
    @Schema(description = "A single crypto position.")
    public record HoldingView(

            @Schema(example = "BTC")
            String symbol,

            @Schema(example = "0.00156250")
            BigDecimal quantity,

            @Schema(description = "The price this was valued at.", example = "64000.00000000")
            BigDecimal price,

            @Schema(description = "quantity * price, to the cent.", example = "100.00")
            BigDecimal estimatedValue) {
    }
}
