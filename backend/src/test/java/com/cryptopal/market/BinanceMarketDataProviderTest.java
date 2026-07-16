package com.cryptopal.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String json = """
                [
                  {"symbol":"BTCUSDT","price":"65000.50"},
                  {"symbol":"ETHUSDT","price":"3200.75"},
                  {"symbol":"SOLUSDT","price":"150.10"},
                  {"symbol":"XRPUSDT","price":"0.50"}
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
    void ignoresPairsThatWereNotRequested() {
        String json = """
                [
                  {"symbol":"BTCUSDT","price":"65000.50"},
                  {"symbol":"DOGEUSDT","price":"0.12"}
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
