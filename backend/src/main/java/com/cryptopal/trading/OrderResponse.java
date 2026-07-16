package com.cryptopal.trading;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a completed trade did.
 *
 * <p>The resulting balance and position are included so the UI can update itself from this
 * response alone. Without them every trade would need a follow-up call to
 * {@code /api/portfolio}, and that second read could land after another order and show a
 * number that never matched this trade.
 */
@Schema(description = "The result of a completed order.")
public record OrderResponse(

        @Schema(description = "Id of the recorded transaction.")
        UUID transactionId,

        @Schema(example = "BTC")
        String symbol,

        OrderSide side,

        @Schema(description = "How much crypto moved. For a BUY this was derived from the fiat "
                + "spent, rounded down to 8 decimals.", example = "0.00156250")
        BigDecimal quantity,

        @Schema(description = "The price the order was filled at.", example = "64000.00000000")
        BigDecimal executionPrice,

        @Schema(description = "How much virtual USD moved.", example = "100.00")
        BigDecimal fiatAmount,

        @Schema(description = "Wallet balance after this order.", example = "49900.00")
        BigDecimal fiatBalance,

        @Schema(description = "Position in this asset after this order.", example = "0.00156250")
        BigDecimal holdingQuantity,

        Instant executedAt) {

    static OrderResponse of(Transaction transaction, BigDecimal fiatBalance, BigDecimal holdingQuantity) {
        return new OrderResponse(
                transaction.getId(),
                transaction.getSymbol(),
                transaction.getSide(),
                transaction.getQuantity(),
                transaction.getExecutionPrice(),
                transaction.getFiatAmount(),
                fiatBalance,
                holdingQuantity,
                transaction.getCreatedAt());
    }
}
