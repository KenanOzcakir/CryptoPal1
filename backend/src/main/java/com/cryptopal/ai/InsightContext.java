package com.cryptopal.ai;

import com.cryptopal.market.PriceQuote;
import com.cryptopal.trading.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything Gemini is allowed to know when answering a question.
 *
 * <p>This exists so the prompt is built from a fixed, inspectable set of facts rather than
 * from whatever a service reached for at the time. If an answer is wrong, the cause is
 * either in here or it is the model making something up, and those are very different
 * bugs.
 *
 * <p>Note what is absent: no password hash, no session token, no other user's anything.
 * Only what the account holder could already see on their own portfolio page.
 */
public record InsightContext(

        /** The asking user's own account, priced at the latest rates. */
        Portfolio portfolio,

        /** Latest price for every supported asset, including ones not held. */
        List<PriceQuote> latestPrices,

        /** Recent movement per asset, worked out here rather than by the model. */
        List<PriceTrend> trends,

        Instant generatedAt) {

    /**
     * How one asset has moved recently, computed from the price snapshot history.
     *
     * <p>The percentage is calculated in Java on purpose. Handing a model two prices and
     * asking what the change was invites arithmetic that reads fluently and is wrong,
     * which is the worst kind of wrong in something that looks like a finance app. The
     * model gets the answer and is asked only to talk about it.
     *
     * @param samples how many snapshots this was drawn from, so the prompt can be honest
     *                about how much history it is actually summarizing
     */
    public record PriceTrend(
            String symbol,
            BigDecimal oldestPrice,
            BigDecimal newestPrice,
            BigDecimal changePercent,
            int samples,
            Instant since) {
    }
}
