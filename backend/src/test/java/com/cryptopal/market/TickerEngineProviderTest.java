package com.cryptopal.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TickerEngineProviderTest {

    private static final Instant FIXED = Instant.parse("2026-07-15T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void returnsOneQuotePerSupportedSymbol() {
        var provider = new TickerEngineProvider();

        List<PriceQuote> quotes = provider.fetchLatestPrices();

        Set<String> symbols = quotes.stream().map(PriceQuote::symbol).collect(Collectors.toSet());
        assertEquals(Set.of("BTC", "ETH", "SOL", "XRP"), symbols);
        assertEquals(4, quotes.size());
    }

    @Test
    void allPricesArePositiveAndStampedWithTheClock() {
        var provider = new TickerEngineProvider(
                Map.of("BTC", new BigDecimal("65000")),
                new BigDecimal("0.01"),
                new Random(42),
                fixedClock);

        for (PriceQuote q : provider.fetchLatestPrices()) {
            assertTrue(q.price().signum() > 0, "price must be positive");
            assertEquals(FIXED, q.quotedAt());
        }
    }

    @Test
    void pricesStayWithinRealisticBandOverManyTicks() {
        BigDecimal base = new BigDecimal("100");
        var provider = new TickerEngineProvider(
                Map.of("TEST", base),
                new BigDecimal("0.05"),
                new Random(7),
                fixedClock);
        BigDecimal floor = base.multiply(new BigDecimal("0.5"));
        BigDecimal ceil = base.multiply(new BigDecimal("2.0"));

        for (int i = 0; i < 10_000; i++) {
            PriceQuote q = provider.fetchLatestPrices().get(0);
            assertTrue(q.price().compareTo(floor) >= 0, "below floor: " + q.price());
            assertTrue(q.price().compareTo(ceil) <= 0, "above ceiling: " + q.price());
        }
    }

    @Test
    void isDeterministicForAGivenSeed() {
        Map<String, BigDecimal> base = Map.of("BTC", new BigDecimal("65000"));
        var a = new TickerEngineProvider(base, new BigDecimal("0.01"), new Random(123), fixedClock);
        var b = new TickerEngineProvider(base, new BigDecimal("0.01"), new Random(123), fixedClock);

        for (int i = 0; i < 100; i++) {
            assertEquals(a.fetchLatestPrices().get(0).price(),
                    b.fetchLatestPrices().get(0).price());
        }
    }

    @Test
    void movesPriceAwayFromBaseOverTime() {
        BigDecimal base = new BigDecimal("100");
        var provider = new TickerEngineProvider(
                Map.of("TEST", base), new BigDecimal("0.02"), new Random(1), fixedClock);

        boolean moved = false;
        for (int i = 0; i < 50 && !moved; i++) {
            if (provider.fetchLatestPrices().get(0).price().compareTo(base) != 0) {
                moved = true;
            }
        }
        assertTrue(moved, "ticker should move prices over time");
    }
}
