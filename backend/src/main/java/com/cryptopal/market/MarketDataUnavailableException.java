package com.cryptopal.market;

/**
 * I throw this when a live provider (Binance) just can't give me prices, whether the network
 * died, it timed out, it returned an error status, or it came back empty. The market layer
 * catches it and falls back to the ticker engine, so one bad Binance call never brings the
 * whole price refresh down.
 */
public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String message) {
        super(message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
