package com.cryptopal.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of Binance's /api/v3/ticker/price reply, e.g. {"symbol":"BTCUSDT","price":"65000.50"}.
 * The price arrives as a String and I turn it into a BigDecimal later, so money never goes
 * through a double.
 */
// @JsonIgnoreProperties(ignoreUnknown = true) tells Jackson to skip any fields I didn't list,
// so Binance adding new fields to the response won't break my parsing.
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceTickerPrice(String symbol, String price) {
}
