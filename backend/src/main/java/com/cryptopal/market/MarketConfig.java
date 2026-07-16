package com.cryptopal.market;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Decides where prices come from.
 *
 * <p>This is the only place that knows Binance exists. Everything downstream talks to
 * {@link MarketDataProvider}, so switching source is a change to one environment variable
 * rather than a change to any code that uses prices.
 */
@Configuration
public class MarketConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketConfig.class);

    /** Bean name for the provider actually in use, as opposed to the ticker fallback. */
    public static final String PRIMARY_PROVIDER = "primaryMarketDataProvider";

    /** Bean name for the offline engine used as the fallback. */
    public static final String TICKER_PROVIDER = "tickerEngineProvider";

    // Binance is normally quick. Five seconds is long enough to absorb a slow response and
    // short enough that a hanging call cannot outlive the 15 second refresh cycle and pile
    // requests on top of each other.
    private static final Duration BINANCE_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public WebClient binanceWebClient(@Value("${BINANCE_BASE_URL}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * The offline engine. Always built, even when Binance is the provider in use, because
     * {@link MarketDataService} falls back to it when Binance fails.
     */
    @Bean(TICKER_PROVIDER)
    public TickerEngineProvider tickerEngineProvider() {
        return new TickerEngineProvider();
    }

    /**
     * The provider that gets asked first, chosen by {@code MARKET_PROVIDER}.
     *
     * <p>Binance is the default because the spec asks for real traded-token rates.
     * {@code MARKET_PROVIDER=ticker} switches to the offline engine, which is useful with
     * no internet, and is the escape hatch if Binance's HTTP 451 geo-block ever becomes a
     * problem where this is hosted.
     */
    @Bean(PRIMARY_PROVIDER)
    public MarketDataProvider primaryMarketDataProvider(
            @Value("${MARKET_PROVIDER}") String mode,
            @Value("${SUPPORTED_SYMBOLS}") List<String> binancePairs,
            WebClient binanceWebClient,
            TickerEngineProvider tickerEngineProvider) {

        if ("ticker".equalsIgnoreCase(mode.trim())) {
            log.info("MARKET_PROVIDER=ticker, prices are simulated and are not real rates");
            return tickerEngineProvider;
        }
        log.info("MARKET_PROVIDER={}, fetching real rates from Binance for {}", mode, binancePairs);
        return new BinanceMarketDataProvider(binanceWebClient, binancePairs, BINANCE_TIMEOUT);
    }
}
