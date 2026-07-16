package com.cryptopal.ai;

import com.cryptopal.market.MarketDataService;
import com.cryptopal.market.PriceQuote;
import com.cryptopal.market.PriceSnapshot;
import com.cryptopal.market.PriceSnapshotRepository;
import com.cryptopal.trading.Portfolio;
import com.cryptopal.trading.PortfolioService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Answers questions about an account, by gathering the facts and asking Gemini to talk
 * about them.
 *
 * <p>The gathering is the interesting half. Anything not collected here is something the
 * model cannot know, and anything collected here is something it might repeat, so this
 * class is effectively the privacy boundary for the feature.
 */
@Service
public class AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(AiInsightService.class);

    // Snapshots land every 15 seconds, so 40 covers roughly the last ten minutes. Enough
    // to say something true about "recently" without stuffing the prompt with hundreds of
    // near-identical numbers, which costs tokens and buys nothing.
    private static final Limit TREND_SAMPLES = Limit.of(40);

    private static final int PERCENT_SCALE = 2;

    private final PortfolioService portfolioService;
    private final MarketDataService marketData;
    private final PriceSnapshotRepository snapshots;
    private final PromptBuilder promptBuilder;
    private final GeminiClient geminiClient;

    public AiInsightService(PortfolioService portfolioService,
                            MarketDataService marketData,
                            PriceSnapshotRepository snapshots,
                            PromptBuilder promptBuilder,
                            GeminiClient geminiClient) {
        this.portfolioService = portfolioService;
        this.marketData = marketData;
        this.snapshots = snapshots;
        this.promptBuilder = promptBuilder;
        this.geminiClient = geminiClient;
    }

    /** Gathers this user's account, builds a prompt, and returns Gemini's answer. */
    public AiInsightResponse ask(UUID userId, String question) {
        InsightContext context = gatherContext(userId);
        String prompt = promptBuilder.buildUserInsightPrompt(context, question);

        // The prompt contains the user's balances and trades, so its length is logged and
        // its contents are not.
        log.debug("Asking Gemini a {} character prompt", prompt.length());

        return new AiInsightResponse(geminiClient.generate(prompt));
    }

    private InsightContext gatherContext(UUID userId) {
        // The user's own portfolio, which already carries the balance, the positions valued
        // at current prices, the total, and the recent trades. Reusing it means the AI
        // sees exactly the same numbers the portfolio page shows, so the two can never
        // disagree in front of someone.
        Portfolio portfolio = portfolioService.getPortfolio(userId);

        List<PriceQuote> latestPrices = marketData.getLatestPrices();
        return new InsightContext(portfolio, latestPrices, trendsFor(latestPrices), Instant.now());
    }

    private List<InsightContext.PriceTrend> trendsFor(List<PriceQuote> latestPrices) {
        List<InsightContext.PriceTrend> trends = new ArrayList<>();
        for (PriceQuote quote : latestPrices) {
            // Newest first, so the last element is the oldest sample.
            List<PriceSnapshot> history =
                    snapshots.findBySymbolOrderByCapturedAtDesc(quote.symbol(), TREND_SAMPLES);
            if (history.size() < 2) {
                // One sample is a price, not a trend. Saying nothing is better than
                // reporting a 0% change that only means the app just started.
                continue;
            }
            PriceSnapshot newest = history.get(0);
            PriceSnapshot oldest = history.get(history.size() - 1);
            trends.add(new InsightContext.PriceTrend(
                    quote.symbol(),
                    oldest.getPrice(),
                    newest.getPrice(),
                    percentChange(oldest.getPrice(), newest.getPrice()),
                    history.size(),
                    oldest.getCapturedAt()));
        }
        return trends;
    }

    /**
     * Percentage change from one price to another, worked out here rather than by the
     * model. Asking an LLM for arithmetic produces confident wrong numbers, and this app
     * is about money.
     */
    private static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from.signum() <= 0) {
            // Cannot divide by it, and the price constraint means it should never happen.
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return to.subtract(from)
                .multiply(BigDecimal.valueOf(100))
                .divide(from, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
