package com.cryptopal.trading;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * A buy or sell order.
 *
 * <p>Read the note on {@code amount} before using this. It is the one field in the whole
 * API whose meaning changes depending on another field, and getting it wrong does not
 * fail, it just does something expensive and different from what was intended.
 */
@Schema(description = "A simulated buy or sell. Note that the meaning of `amount` depends on `side`.")
public record OrderRequest(

        @Schema(description = "Asset symbol. Case insensitive.", example = "BTC")
        @NotBlank(message = "must not be blank")
        String symbol,

        @Schema(description = "BUY or SELL. Case insensitive.", example = "BUY")
        @NotNull(message = "must be BUY or SELL")
        OrderSide side,

        @Schema(description = """
                What this means depends on `side`:

                - **BUY**: the amount of virtual USD to **spend**. `100` means "spend 100 \
                dollars", and how much crypto that buys is worked out from the current price.
                - **SELL**: the **quantity of crypto** to sell. `0.5` means "sell half a coin", \
                and how much fiat that returns is worked out from the current price.

                The two are not interchangeable. Sending `100` to a SELL asks to sell 100 \
                whole coins, not 100 dollars' worth.
                """, example = "100.00")
        @NotNull(message = "must not be null")
        // Strictly greater than zero. A zero order is a no-op and a negative one would be
        // the same as an order in the opposite direction, which side already decides.
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
        BigDecimal amount) {
}
