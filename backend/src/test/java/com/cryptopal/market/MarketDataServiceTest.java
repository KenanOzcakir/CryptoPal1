package com.cryptopal.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the caching, the refusals, and the Binance-to-ticker fallback, against real Redis
 * and real PostgreSQL. Needs {@code docker compose up -d}.
 *
 * <p>The service under test is built by hand rather than injected, for two reasons: the
 * provider can then be a stub that fails on demand, and the assets can be ones the real
 * application does not trade. That second part matters. The application's own scheduler is
 * running throughout this test, writing {@code price:BTC} and friends every 15 seconds, so
 * a test that touched those keys would be racing it. ADA and DOT are never written by
 * anything but this test.
 *
 * <p>The application's own MarketDataService bean is replaced with a mock, purely to stop
 * its 15 second scheduler running. This is not squeamishness about the network: the
 * scheduler writes a snapshot row per asset per tick into the real database, so every run
 * of this suite was appending prices to the same history the AI module reads. Worse, when
 * this test forced MARKET_PROVIDER=ticker, those rows were invented ones, landing next to
 * real Binance rates with nothing to tell them apart. ETH was recorded at both 1872 and
 * 3223 on the same afternoon.
 *
 * <p>Nothing is lost by mocking it, because every test below builds its own
 * MarketDataService by hand anyway.
 */
@SpringBootTest
class MarketDataServiceTest {

    // Only here to keep the real one, and its scheduler, from starting. See above.
    @MockitoBean
    private MarketDataService applicationScheduler;

    // Deliberately not the four real assets. See the note above about the live scheduler.
    private static final List<String> TEST_PAIRS = List.of("ADAUSDT", "DOTUSDT");
    private static final Instant FIXED = Instant.parse("2026-07-16T12:00:00Z");

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PriceSnapshotRepository snapshots;
    @Autowired
    private ObjectMapper json;

    private TickerEngineProvider ticker;

    @BeforeEach
    void setUp() {
        // A seeded Random and a fixed Clock, so the invented prices are the same each run.
        ticker = new TickerEngineProvider(
                Map.of("ADA", new BigDecimal("0.50"), "DOT", new BigDecimal("6.00")),
                new BigDecimal("0.01"),
                new Random(42),
                Clock.fixed(FIXED, ZoneOffset.UTC));
        clearTestData();
    }

    @AfterEach
    void tearDown() {
        clearTestData();
    }

    @Test
    void refreshCachesEveryPriceInRedisWithATtl() {
        var service = serviceWith(ticker);

        service.refreshPrices();

        for (String symbol : List.of("ADA", "DOT")) {
            String cached = redis.opsForValue().get("price:" + symbol);
            assertThat(cached).as("price for %s", symbol).isNotNull();

            Long ttl = redis.getExpire("price:" + symbol, TimeUnit.SECONDS);
            // A key with no expiry reports -1. That is the bug this asserts against:
            // without a TTL, a stopped scheduler would leave these prices readable
            // forever and trades would execute against an hours-old number.
            assertThat(ttl).as("ttl for %s", symbol).isNotNull().isPositive().isLessThanOrEqualTo(60);
        }
    }

    @Test
    void refreshWritesOneSnapshotPerAsset() {
        var service = serviceWith(ticker);

        service.refreshPrices();

        assertThat(snapshots.findBySymbolOrderByCapturedAtDesc("ADA", org.springframework.data.domain.Limit.of(10)))
                .hasSize(1)
                .first()
                .satisfies(snapshot -> {
                    assertThat(snapshot.getPrice()).isPositive();
                    assertThat(snapshot.getCapturedAt()).isEqualTo(FIXED);
                });
    }

    @Test
    void theTickerCatchesBinanceWhenItFails() {
        // The reason the ticker engine is kept at all: Binance answers HTTP 451 to some
        // regions, rate limits, and occasionally is simply down, and none of that should
        // leave a live demo with nothing on screen.
        MarketDataProvider brokenBinance = () -> {
            throw new MarketDataUnavailableException("Binance price request failed");
        };
        var service = serviceWith(brokenBinance, ticker);

        service.refreshPrices();

        // Prices are there anyway, and they came from the ticker.
        assertThat(redis.opsForValue().get("price:ADA")).isNotNull();
        assertThat(service.getLatestPrice("ADA").price()).isPositive();
    }

    @Test
    void whenTheTickerIsAlreadyThePrimaryItIsNotAskedTwice() {
        // With MARKET_PROVIDER=ticker the primary and the fallback are the same object, so
        // asking it again after it just failed would only fail again. The counter is the
        // assertion: one call, not two.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MarketDataProvider brokenTicker = () -> {
            calls.incrementAndGet();
            throw new MarketDataUnavailableException("the ticker itself broke");
        };
        var service = new MarketDataService(brokenTicker, brokenTicker, redis, snapshots, json, TEST_PAIRS);

        assertThatCode(service::refreshPrices).doesNotThrowAnyException();

        assertThat(calls.get()).as("asked once, with no pointless retry against itself").isEqualTo(1);
        assertThat(redis.opsForValue().get("price:ADA")).isNull();
    }

    @Test
    void aFailedRefreshNeverEscapesTheScheduler() {
        // An exception thrown out of a @Scheduled method is not something the caller can
        // handle, and one bad response must not take the price feed down with it.
        MarketDataProvider exploding = () -> {
            throw new IllegalStateException("something entirely unexpected");
        };
        var service = serviceWith(exploding);

        assertThatCode(service::refreshPrices).doesNotThrowAnyException();
    }

    @Test
    void anAssetWeDoNotTradeIsRejectedAsUnsupported() {
        var service = serviceWith(ticker);
        service.refreshPrices();

        // 400, not 503. This one will never work, so telling the caller to retry would be
        // a lie.
        assertThatThrownBy(() -> service.getLatestPrice("DOGE"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UNSUPPORTED_SYMBOL);
    }

    @Test
    void aSupportedAssetWithNothingCachedIsUnavailableRatherThanWrong() {
        var service = serviceWith(ticker);
        // Nothing refreshed yet, so the cache is empty for these assets.

        // 503, not 400, and not a guess. Trading must never proceed on an invented price.
        assertThatThrownBy(() -> service.getLatestPrice("ADA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PRICE_UNAVAILABLE);
    }

    @Test
    void symbolsAreCaseAndSpaceInsensitive() {
        var service = serviceWith(ticker);
        service.refreshPrices();

        assertThat(service.getLatestPrice("  ada  ").symbol()).isEqualTo("ADA");
    }

    private MarketDataService serviceWith(MarketDataProvider primary) {
        return serviceWith(primary, ticker);
    }

    private MarketDataService serviceWith(MarketDataProvider primary, TickerEngineProvider fallback) {
        return new MarketDataService(primary, fallback, redis, snapshots, json, TEST_PAIRS);
    }

    private void clearTestData() {
        redis.delete(List.of("price:ADA", "price:DOT"));
        snapshots.deleteAll(snapshots.findAll().stream()
                .filter(snapshot -> List.of("ADA", "DOT").contains(snapshot.getSymbol()))
                .toList());
    }
}
