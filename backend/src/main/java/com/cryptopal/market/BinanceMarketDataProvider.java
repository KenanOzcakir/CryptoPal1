package com.cryptopal.market;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * BinanceMarketDataProvider is my live price source, backed by Binance's public Spot API. I
 * grab every coin I care about in one batched call to /api/v3/ticker/24hr?symbols=[...], turn
 * each Binance pair back into my own symbol (BTCUSDT -> BTC), and parse the numbers into
 * BigDecimal. I only ever READ prices here, the app never places real orders, all the buying
 * and selling is simulated on my side.
 *
 * I started on /api/v3/ticker/price, which returns nothing but a symbol and a price. I moved
 * to /24hr so the market table has a change and a volume to show: a page of four bare prices
 * tells a user nothing about whether anything is moving. It is the same batched call and the
 * same weight budget, just a fatter row.
 *
 * I picked Binance over CoinGecko for the deployed app because it has no monthly call cap, so
 * my 15-second refresh can run all day (see DECISIONS.md #9a). If anything goes wrong I throw
 * MarketDataUnavailableException so the layer above can fall back to the ticker engine. The
 * WebClient and Clock are injected so I can unit test toQuotes() with zero network.
 */
public class BinanceMarketDataProvider implements MarketDataProvider {

    private static final ParameterizedTypeReference<List<Binance24hrTicker>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final String QUOTE_ASSET = "USDT";

    private final WebClient webClient;
    private final List<String> pairs;
    private final Duration timeout;
    private final Clock clock;

    // pairs are the Binance symbols I want, e.g. ["BTCUSDT","ETHUSDT"].
    public BinanceMarketDataProvider(WebClient webClient, List<String> pairs, Duration timeout) {
        this(webClient, pairs, timeout, Clock.systemUTC());
    }

    BinanceMarketDataProvider(WebClient webClient, List<String> pairs, Duration timeout, Clock clock) {
        if (pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException("pairs must not be empty");
        }
        this.webClient = webClient;
        this.pairs = List.copyOf(pairs);
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public List<PriceQuote> fetchLatestPrices() {
        List<Binance24hrTicker> rows;
        try {
            // One GET for all the pairs at once, so I'm not hammering Binance with N calls.
            rows = webClient.get()
                    .uri(builder -> builder
                            .path("/api/v3/ticker/24hr")
                            .queryParam("symbols", symbolsParam())
                            .build())
                    .retrieve()
                    .bodyToMono(LIST_TYPE)
                    .block(timeout);
        } catch (RuntimeException e) {
            // Wrap whatever went wrong (timeout, 5xx, connection dropped...) into my own
            // exception so callers only have one thing to catch.
            throw new MarketDataUnavailableException("Binance price request failed", e);
        }
        if (rows == null || rows.isEmpty()) {
            throw new MarketDataUnavailableException("Binance returned no prices");
        }
        return toQuotes(rows, Instant.now(clock));
    }

    // Turn Binance's rows into my PriceQuotes: keep only the pairs I asked for, map
    // SYMBOLUSDT -> SYMBOL, parse the numbers, and drop anything non-positive.
    // Package-private on purpose so I can test this bit without any HTTP.
    List<PriceQuote> toQuotes(List<Binance24hrTicker> rows, Instant quotedAt) {
        // A quick lookup of the pairs I care about -> their plain symbol.
        Map<String, String> pairToAsset = new LinkedHashMap<>();
        for (String pair : pairs) {
            pairToAsset.put(pair.toUpperCase(Locale.ROOT), toAsset(pair));
        }
        List<PriceQuote> quotes = new ArrayList<>();
        for (Binance24hrTicker row : rows) {
            if (row == null || row.symbol() == null || row.lastPrice() == null) {
                continue;
            }
            String asset = pairToAsset.get(row.symbol().toUpperCase(Locale.ROOT));
            if (asset == null) {
                continue; // a pair I didn't ask for -> skip it
            }
            BigDecimal price = new BigDecimal(row.lastPrice());
            if (price.signum() <= 0) {
                continue; // PriceQuote won't accept a non-positive price, so I skip it
            }
            // The change and the volume are decoration, so a missing or unreadable one must
            // never cost me the price itself. A row with a good price and a mangled volume
            // is still worth having.
            quotes.add(new PriceQuote(asset, price, quotedAt,
                    parseOrNull(row.priceChangePercent()),
                    parseOrNull(row.quoteVolume())));
        }
        return quotes;
    }

    // Binance sends its numbers as Strings, and I keep them that way until here so nothing
    // passes through a double on the way. Null rather than an exception on anything odd:
    // see the note above about not losing a price over a broken volume.
    private static BigDecimal parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Binance wants the symbols as a JSON array string, like ["BTCUSDT","ETHUSDT"].
    private String symbolsParam() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(pairs.get(i).toUpperCase(Locale.ROOT)).append('"');
        }
        return sb.append(']').toString();
    }

    // Strip the USDT quote off a pair, so "BTCUSDT" becomes "BTC".
    // Package-private rather than private so MarketDataService can work out which assets
    // I support from the same SUPPORTED_SYMBOLS list, without a second copy of this rule.
    // It lives here because this is the class that knows what the quote asset is.
    static String toAsset(String pair) {
        String p = pair.trim().toUpperCase(Locale.ROOT);
        return p.endsWith(QUOTE_ASSET) ? p.substring(0, p.length() - QUOTE_ASSET.length()) : p;
    }
}
