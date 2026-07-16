package com.cryptopal.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class BinanceMarketDataProviderTest {

    private static final Instant FIXED = Instant.parse("2026-07-15T12:00:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    private final List<String> pairs = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT");

    // A WebClient whose every request resolves to the given canned response, no network involved.
    private WebClient stubClient(String body, HttpStatus status) {
        ExchangeFunction exchange = request -> Mono.just(
                ClientResponse.create(status)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build());
        return WebClient.builder()
                .baseUrl("https://api.binance.com")
                .exchangeFunction(exchange)
                .build();
    }

    @Test
    void mapsBinancePairsToInternalQuotes() {
        // Trimmed rows from /api/v3/ticker/24hr. The real reply carries about twenty fields
        // per row, and the ones left out here are exactly the ones that must not matter.
        String json = """
                [
                  {"symbol":"BTCUSDT","lastPrice":"65000.50","priceChangePercent":"-1.156","quoteVolume":"1378687174.87"},
                  {"symbol":"ETHUSDT","lastPrice":"3200.75","priceChangePercent":"2.041","quoteVolume":"884120.10"},
                  {"symbol":"SOLUSDT","lastPrice":"150.10","priceChangePercent":"0.000","quoteVolume":"55120.00"},
                  {"symbol":"XRPUSDT","lastPrice":"0.50","priceChangePercent":"-0.315","quoteVolume":"9912.42"}
                ]
                """;
        var provider = new BinanceMarketDataProvider(
                stubClient(json, HttpStatus.OK), pairs, Duration.ofSeconds(5), clock);

        List<PriceQuote> quotes = provider.fetchLatestPrices();

        Map<String, BigDecimal> bySymbol = quotes.stream()
                .collect(Collectors.toMap(PriceQuote::symbol, PriceQuote::price));
        assertEquals(new BigDecimal("65000.50"), bySymbol.get("BTC"));
        assertEquals(new BigDecimal("3200.75"), bySymbol.get("ETH"));
        assertEquals(new BigDecimal("150.10"), bySymbol.get("SOL"));
        assertEquals(new BigDecimal("0.50"), bySymbol.get("XRP"));
        quotes.forEach(q -> assertEquals(FIXED, q.quotedAt()));
    }

    @Test
    void carriesThe24HourChangeAndVolumeThrough() {
        String json = """
                [{"symbol":"BTCUSDT","lastPrice":"65000.50","priceChangePercent":"-1.156","quoteVolume":"1378687174.87"}]
                """;
        var provider = new BinanceMarketDataProvider(
                stubClient(json, HttpStatus.OK), List.of("BTCUSDT"), Duration.ofSeconds(5), clock);

        PriceQuote quote = provider.fetchLatestPrices().get(0);

        // Straight from Binance, not worked out here. BigDecimal all the way, so a price
        // never passes through a double.
        assertEquals(new BigDecimal("-1.156"), quote.changePercent24h());
        assertEquals(new BigDecimal("1378687174.87"), quote.volume24h());
    }

    @Test
    void aBrokenChangeOrVolumeNeverCostsMeThePrice() {
        // The price is the only field that matters. The other two are decoration, so a
        // mangled one must degrade to nothing rather than take the whole row down.
        String json = """
                [{"symbol":"BTCUSDT","lastPrice":"65000.50","priceChangePercent":"not-a-number","quoteVolume":""}]
                """;
        var provider = new BinanceMarketDataProvider(
                stubClient(json, HttpStatus.OK), List.of("BTCUSDT"), Duration.ofSeconds(5), clock);

        PriceQuote quote = provider.fetchLatestPrices().get(0);

        assertEquals(new BigDecimal("65000.50"), quote.price());
        assertNull(quote.changePercent24h());
        assertNull(quote.volume24h());
    }

    @Test
    void unknownBinanceFieldsAreIgnored() {
        // The real reply has bidPrice, askPrice, count, openTime and more. Binance adding a
        // field should never break this.
        String json = """
                [{"symbol":"BTCUSDT","lastPrice":"65000.50","priceChangePercent":"-1.156",
                  "quoteVolume":"1378687174.87","bidPrice":"65000.49","count":812345,
                  "somethingBinanceAddedLater":"whatever"}]
                """;
        var provider = new BinanceMarketDataProvider(
                stubClient(json, HttpStatus.OK), List.of("BTCUSDT"), Duration.ofSeconds(5), clock);

        assertEquals(new BigDecimal("65000.50"), provider.fetchLatestPrices().get(0).price());
    }

    @Test
    void ignoresPairsThatWereNotRequested() {
        String json = """
                [
                  {"symbol":"BTCUSDT","lastPrice":"65000.50","priceChangePercent":"-1.156","quoteVolume":"1.0"},
                  {"symbol":"DOGEUSDT","lastPrice":"0.12","priceChangePercent":"5.000","quoteVolume":"1.0"}
                ]
                """;
        var provider = new BinanceMarketDataProvider(
                stubClient(json, HttpStatus.OK), List.of("BTCUSDT"), Duration.ofSeconds(5), clock);

        List<PriceQuote> quotes = provider.fetchLatestPrices();

        assertEquals(1, quotes.size());
        assertEquals("BTC", quotes.get(0).symbol());
    }

    @Test
    void throwsWhenBinanceReturnsErrorStatus() {
        var provider = new BinanceMarketDataProvider(
                stubClient("{\"code\":-1121}", HttpStatus.SERVICE_UNAVAILABLE), pairs, Duration.ofSeconds(5), clock);

        assertThrows(MarketDataUnavailableException.class, provider::fetchLatestPrices);
    }

    @Test
    void throwsWhenResponseIsEmpty() {
        var provider = new BinanceMarketDataProvider(
                stubClient("[]", HttpStatus.OK), pairs, Duration.ofSeconds(5), clock);

        assertThrows(MarketDataUnavailableException.class, provider::fetchLatestPrices);
    }
}
