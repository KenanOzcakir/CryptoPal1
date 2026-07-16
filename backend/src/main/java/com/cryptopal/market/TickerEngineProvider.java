package com.cryptopal.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * TickerEngineProvider is my offline "fake market". It makes up realistic-looking prices so
 * the whole app can run and demo with no internet and no Binance. Each time I'm asked for
 * prices I nudge every one by a small random percentage and keep it inside a sensible band
 * around its starting price, so the numbers wander like a real market but never go negative
 * or shoot off to nonsense values.
 *
 * This is not the provider I normally run: MARKET_PROVIDER defaults to binance, because the
 * spec wants real traded-token rates. This one earns its place two ways. MarketDataService
 * falls back to it automatically when Binance fails, so a dead network never leaves the app
 * with no prices mid-demo, and MARKET_PROVIDER=ticker selects it outright for working
 * offline. Its prices are invented, so nothing here is a real market rate.
 *
 * The base prices below are the four assets I support. If SUPPORTED_SYMBOLS ever changes,
 * this map has to change with it, since there is nothing to fetch the list from when the
 * whole point is running with no internet.
 *
 * I keep Spring out of this class on purpose so it's trivial to unit test, and I pass in the
 * Random and Clock from outside so a test can pin them down and get the same result every run.
 */
public class TickerEngineProvider implements MarketDataProvider {

    // The prices I start each asset at (roughly real, in virtual USD).
    private static final Map<String, BigDecimal> DEFAULT_BASE_PRICES = Map.of(
            "BTC", new BigDecimal("65000"),
            "ETH", new BigDecimal("3200"),
            "SOL", new BigDecimal("150"),
            "XRP", new BigDecimal("0.50")
    );

    // The most a price is allowed to move in a single tick (0.01 = 1%).
    private static final BigDecimal DEFAULT_MAX_DRIFT = new BigDecimal("0.01");

    // I clamp every price to this band around its base so it stays believable:
    // never below 50% or above 200% of where it started.
    private static final BigDecimal LOWER_BAND = new BigDecimal("0.5");
    private static final BigDecimal UPPER_BAND = new BigDecimal("2.0");

    private static final int PRICE_SCALE = 8;

    private final Map<String, BigDecimal> basePrices;    // fixed reference points I clamp against
    private final Map<String, BigDecimal> currentPrices; // the live prices that drift each tick
    private final BigDecimal maxDrift;
    private final Random random;
    private final Clock clock;

    // Normal use: default coins, real randomness, real clock.
    public TickerEngineProvider() {
        this(DEFAULT_BASE_PRICES, DEFAULT_MAX_DRIFT, new Random(), Clock.systemUTC());
    }

    // The full constructor is mainly here so my tests can pass a seeded Random and a fixed
    // Clock and get predictable prices back.
    public TickerEngineProvider(Map<String, BigDecimal> basePrices,
                                BigDecimal maxDrift,
                                Random random,
                                Clock clock) {
        if (basePrices.isEmpty()) {
            throw new IllegalArgumentException("basePrices must not be empty");
        }
        this.basePrices = new LinkedHashMap<>(basePrices);
        this.currentPrices = new LinkedHashMap<>(basePrices);
        this.maxDrift = maxDrift;
        this.random = random;
        this.clock = clock;
    }

    @Override
    public List<PriceQuote> fetchLatestPrices() {
        // I stamp them all with one "now" so a single fetch is internally consistent.
        Instant now = Instant.now(clock);
        List<PriceQuote> quotes = new ArrayList<>(currentPrices.size());
        for (Map.Entry<String, BigDecimal> entry : currentPrices.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal next = nextPrice(symbol, entry.getValue());
            currentPrices.put(symbol, next); // remember it so next tick drifts from here
            quotes.add(new PriceQuote(symbol, next, now));
        }
        return quotes;
    }

    // Take one price for a small random walk, then pull it back into its band if it wandered off.
    private BigDecimal nextPrice(String symbol, BigDecimal current) {
        double factor = random.nextDouble() * 2.0 - 1.0;              // somewhere in [-1, 1)
        BigDecimal delta = maxDrift.multiply(BigDecimal.valueOf(factor));
        BigDecimal candidate = current.add(current.multiply(delta));  // current * (1 + delta)

        // Keep it realistic -> clamp to [50%, 200%] of the base price.
        BigDecimal base = basePrices.get(symbol);
        BigDecimal floor = base.multiply(LOWER_BAND);
        BigDecimal ceil = base.multiply(UPPER_BAND);
        if (candidate.compareTo(floor) < 0) {
            candidate = floor;
        } else if (candidate.compareTo(ceil) > 0) {
            candidate = ceil;
        }
        return candidate.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
