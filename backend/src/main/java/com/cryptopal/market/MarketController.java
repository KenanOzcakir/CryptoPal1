package com.cryptopal.market;

import com.cryptopal.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price endpoints, and the only public part of the API.
 *
 * <p>These need no session. The rates are the same public exchange data anyone can read
 * from Binance directly, so a login would protect nothing and would stop the landing page
 * showing anything worth looking at.
 *
 * <p>Prices are served from Redis, never from PostgreSQL. The snapshot table is history,
 * and reading the newest row out of a growing table on every poll would be slower work for
 * a worse answer.
 */
@RestController
@RequestMapping("/api/market")
@Tag(name = "Market", description = "Latest cryptocurrency prices. Public, no token needed.")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/prices")
    @Operation(summary = "Latest price for every supported asset",
            description = "Served from the Redis cache, refreshed every 15 seconds from the "
                    + "Binance Spot API. Prices are real exchange rates, but no order placed "
                    + "through this application ever reaches Binance. If Binance is "
                    + "unreachable the values come from a local simulation instead, so they "
                    + "should not be treated as authoritative.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest prices"),
            @ApiResponse(responseCode = "503", description = "PRICE_UNAVAILABLE, nothing cached yet",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<PriceQuote> getLatestPrices() {
        // PriceQuote is returned straight out rather than copied into a PriceQuoteDto as
        // the architecture sketch had it. It is already an immutable record of exactly
        // symbol, price, and quotedAt, which is the documented response, so a DTO would be
        // the same three fields under another name. Same reasoning as DECISIONS.md 5.
        return marketDataService.getLatestPrices();
    }

    @GetMapping("/prices/{symbol}")
    @Operation(summary = "Latest price for one asset")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest price"),
            @ApiResponse(responseCode = "400", description = "UNSUPPORTED_SYMBOL, not an asset we trade",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRICE_UNAVAILABLE, no price cached yet",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PriceQuote getPrice(
            @Parameter(description = "Asset symbol, for example BTC. Case insensitive.", example = "BTC")
            @PathVariable String symbol) {
        return marketDataService.getLatestPrice(symbol);
    }
}
