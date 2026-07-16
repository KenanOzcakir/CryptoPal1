package com.cryptopal.market;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the latest prices fresh, and is the only way the rest of the app reads one.
 *
 * <p>Every 15 seconds it asks the configured provider for prices, overwrites them in Redis,
 * and appends a row per asset to PostgreSQL. That split is the whole design: Redis answers
 * the "what is BTC worth right now" question that trading and the price page ask constantly,
 * while Postgres accumulates the history the AI module reads. Redis is a speed layer that
 * may vanish; Postgres is the record.
 *
 * <p>Trading, portfolio valuation, and AI all read prices through here rather than touching
 * Redis themselves, so there is one place that decides what "the current price" means and
 * one place that refuses when there isn't one.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private static final String KEY_PREFIX = "price:";

    // The 15 second refresh the spec asks for. A literal because @Scheduled needs a
    // constant, so it cannot read the Duration below.
    private static final long REFRESH_INTERVAL_MILLIS = 15_000;

    // Cached prices expire after four missed refreshes. This matters more than it looks:
    // without it, a stopped scheduler or a provider that stays broken would leave the last
    // known prices in Redis forever, and trades would keep executing against a number from
    // hours ago while looking perfectly healthy. Expiring them turns a silent wrong answer
    // into an honest PRICE_UNAVAILABLE.
    private static final Duration PRICE_TTL = Duration.ofSeconds(60);

    private final MarketDataProvider provider;
    private final MarketDataProvider fallback;
    private final StringRedisTemplate redis;
    private final PriceSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;
    private final Set<String> supportedSymbols;

    // Both providers are typed as the interface rather than the concrete
    // TickerEngineProvider. That keeps this class ignorant of which implementation it got,
    // and it means a test can hand in a provider that fails on demand, including as both
    // arguments at once, which is the only way to exercise the "nothing to fall back to"
    // branch below.
    public MarketDataService(@Qualifier(MarketConfig.PRIMARY_PROVIDER) MarketDataProvider provider,
                             @Qualifier(MarketConfig.TICKER_PROVIDER) MarketDataProvider fallback,
                             StringRedisTemplate redis,
                             PriceSnapshotRepository snapshots,
                             ObjectMapper objectMapper,
                             @Value("${SUPPORTED_SYMBOLS}") List<String> binancePairs) {
        this.provider = provider;
        this.fallback = fallback;
        this.redis = redis;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        // SUPPORTED_SYMBOLS is written in Binance pairs, so BTCUSDT becomes BTC. Kept in a
        // LinkedHashSet so /api/market/prices always lists assets in configured order
        // rather than shuffling between calls and making the UI jump.
        this.supportedSymbols = binancePairs.stream()
                .map(BinanceMarketDataProvider::toAsset)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Fetches, caches, and records the latest prices. Runs immediately at startup and every
     * 15 seconds after, so the cache is seeded without a separate warm-up step.
     */
    @Scheduled(fixedRate = REFRESH_INTERVAL_MILLIS)
    public void refreshPrices() {
        try {
            List<PriceQuote> quotes = fetchWithFallback();
            quotes.forEach(this::cache);
            snapshots.saveAll(quotes.stream().map(PriceSnapshot::from).toList());
            log.debug("Refreshed {} prices", quotes.size());
        } catch (RuntimeException e) {
            // Nothing escapes this method. An exception thrown out of a scheduled task
            // kills nothing gracefully: the previous prices simply stay put until their TTL
            // expires, which is the honest outcome. Crashing the refresh loop over one bad
            // response would take the whole price feed down with it.
            log.error("Price refresh failed, keeping whatever is already cached", e);
        }
    }

    /** Every asset with a cached price, in configured order. */
    public List<PriceQuote> getLatestPrices() {
        List<String> keys = supportedSymbols.stream().map(symbol -> KEY_PREFIX + symbol).toList();
        // One MGET rather than a GET per symbol: the price page polls this constantly, and
        // four round trips where one will do adds up.
        List<String> cached = redis.opsForValue().multiGet(keys);
        List<PriceQuote> quotes = cached == null ? List.of()
                : cached.stream().filter(Objects::nonNull).map(this::deserialize).toList();

        if (quotes.isEmpty()) {
            // Nothing cached at all means the app has only just started, or every refresh
            // has failed for a minute. Either way there is no honest answer to give.
            throw new ApiException(ErrorCode.PRICE_UNAVAILABLE,
                    "No prices are available right now, please try again in a moment");
        }
        return quotes;
    }

    /** The latest price for one asset. This is what trading and valuation call. */
    public PriceQuote getLatestPrice(String symbol) {
        String asset = normalize(symbol);
        if (!supportedSymbols.contains(asset)) {
            // Deliberately a different answer to the one below. "We do not trade that" is
            // the caller's mistake and will never work; "no price yet" is our problem and
            // will fix itself in seconds.
            throw new ApiException(ErrorCode.UNSUPPORTED_SYMBOL,
                    "Not a supported asset: " + asset + ". Supported: " + supportedSymbols);
        }
        String cached = redis.opsForValue().get(KEY_PREFIX + asset);
        if (cached == null) {
            throw new ApiException(ErrorCode.PRICE_UNAVAILABLE,
                    "No price for " + asset + " is available right now, please try again in a moment");
        }
        return deserialize(cached);
    }

    /** The assets this application trades, in configured order. */
    public Set<String> supportedSymbols() {
        return Set.copyOf(supportedSymbols);
    }

    private List<PriceQuote> fetchWithFallback() {
        try {
            return provider.fetchLatestPrices();
        } catch (MarketDataUnavailableException e) {
            if (provider == fallback) {
                // Already running the ticker engine, so there is nothing further to fall
                // back to. It generates prices locally and should never reach here.
                throw e;
            }
            // The reason the ticker engine exists. Binance answers HTTP 451 to some
            // regions and can rate limit or simply be down, and none of that should leave
            // a live demo with no prices on screen.
            log.warn("Binance is unavailable ({}), using the ticker engine for this cycle. "
                    + "Prices are simulated until it recovers.", e.getMessage());
            return fallback.fetchLatestPrices();
        }
    }

    private void cache(PriceQuote quote) {
        redis.opsForValue().set(KEY_PREFIX + quote.symbol(), objectMapper.writeValueAsString(quote), PRICE_TTL);
    }

    private PriceQuote deserialize(String json) {
        try {
            return objectMapper.readValue(json, PriceQuote.class);
        } catch (JacksonException e) {
            // Only reachable if something else wrote into the price key space, or if
            // PriceQuote changed shape while old values were still cached. Refusing beats
            // guessing a number that money will be moved against.
            log.warn("Discarding unreadable cached price", e);
            throw new ApiException(ErrorCode.PRICE_UNAVAILABLE, "Cached price could not be read", e);
        }
    }

    private static String normalize(String symbol) {
        // Locale.ROOT for the same reason as in AuthService: on a Turkish locale a default
        // toUpperCase turns "i" into a dotted capital and the symbol stops matching.
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
