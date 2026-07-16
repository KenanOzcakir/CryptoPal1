package com.cryptopal.trading;

import com.cryptopal.auth.AuthenticatedUser;
import com.cryptopal.auth.SessionAuthFilter;
import com.cryptopal.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trading, portfolio, and history. All three require a session.
 *
 * <p>The user is taken from the request attribute rather than from anything in the body or
 * the path. That is the point: a caller cannot ask to trade on someone else's behalf,
 * because the only user this controller can see is the one
 * {@link SessionAuthFilter} resolved from their own token.
 */
@RestController
@Tag(name = "Trading", description = "Simulated orders, portfolio, and trade history. Session required.")
public class TradingController {

    private final TradingService tradingService;
    private final PortfolioService portfolioService;

    public TradingController(TradingService tradingService, PortfolioService portfolioService) {
        this.tradingService = tradingService;
        this.portfolioService = portfolioService;
    }

    @PostMapping("/api/orders")
    @Operation(summary = "Place a simulated buy or sell",
            description = """
                    Executes immediately at the latest cached price. Nothing reaches a real \
                    exchange.

                    **The `amount` field means different things depending on `side`.** For a \
                    BUY it is the virtual USD to spend; for a SELL it is the quantity of \
                    crypto to sell. Sending a dollar amount to a SELL will try to sell that \
                    many whole coins.

                    The whole order is one database transaction holding a write lock on the \
                    wallet, so concurrent orders cannot corrupt a balance.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order executed"),
            @ApiResponse(responseCode = "400",
                    description = "VALIDATION_ERROR, INSUFFICIENT_FUNDS, INSUFFICIENT_HOLDINGS, or UNSUPPORTED_SYMBOL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRICE_UNAVAILABLE, no price cached to trade at",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse executeOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestAttribute(SessionAuthFilter.AUTH_USER_ATTRIBUTE) AuthenticatedUser user) {
        return tradingService.executeOrder(user.userId(), request);
    }

    @GetMapping("/api/portfolio")
    @Operation(summary = "Wallet, positions, and recent activity",
            description = "Positions are valued at the latest cached prices, so every value "
                    + "except the fiat balance is an estimate that moves with the market.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The portfolio"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRICE_UNAVAILABLE, a held asset has no price to value it",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Portfolio getPortfolio(
            @RequestAttribute(SessionAuthFilter.AUTH_USER_ATTRIBUTE) AuthenticatedUser user) {
        return portfolioService.getPortfolio(user.userId());
    }

    @GetMapping("/api/transactions")
    @Operation(summary = "Trade history, newest first",
            description = "Capped at the 50 most recent trades.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The trades"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<TransactionView> getTransactions(
            @RequestAttribute(SessionAuthFilter.AUTH_USER_ATTRIBUTE) AuthenticatedUser user) {
        return portfolioService.getTransactions(user.userId());
    }
}
