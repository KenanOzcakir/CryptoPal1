package com.cryptopal.trading;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One trade, as the API reports it.
 *
 * <p>A record rather than the {@link Transaction} entity itself. Serializing entities
 * straight out ties the wire format to the database schema, so a column rename becomes a
 * breaking API change, and it hands Jackson a managed object to walk.
 */
@Schema(description = "A completed trade.")
public record TransactionView(

        UUID id,

        @Schema(example = "BTC")
        String symbol,

        OrderSide side,

        @Schema(description = "How much crypto moved. Always positive: side says which way.",
                example = "0.00156250")
        BigDecimal quantity,

        @Schema(description = "The price it was filled at.", example = "64000.00000000")
        BigDecimal executionPrice,

        @Schema(description = "How much virtual USD moved.", example = "100.00")
        BigDecimal fiatAmount,

        Instant createdAt) {

    static TransactionView from(Transaction transaction) {
        return new TransactionView(
                transaction.getId(),
                transaction.getSymbol(),
                transaction.getSide(),
                transaction.getQuantity(),
                transaction.getExecutionPrice(),
                transaction.getFiatAmount(),
                transaction.getCreatedAt());
    }
}
