package com.cryptopal.market;

import java.util.List;

/**
 * MarketDataProvider is the one seam I put between the app and "where prices come from".
 * Whether they come from Binance or my local ticker engine, the rest of the code only ever
 * talks to this interface, so I can swap the source with a config flag and nothing else has
 * to change.
 */
public interface MarketDataProvider {

    // Give me the latest price for every asset I support, freshly fetched.
    List<PriceQuote> fetchLatestPrices();
}
