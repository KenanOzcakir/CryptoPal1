package com.cryptopal.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MoneyMath is my one place for all the money and quantity arithmetic. I route every
 * calculation through here so the rounding is always the same and I can point to exactly where
 * it happens, and so money never touches a double. The rules (from DECISIONS.md): crypto
 * quantities use 8 decimals and a buy rounds DOWN so a trade can never hand out more coin than
 * was actually paid for; fiat uses 2 decimals, rounded the normal way (HALF_UP) for valuation.
 */
public final class MoneyMath {

    // Decimals I keep fiat (virtual USD) amounts at.
    public static final int FIAT_SCALE = 2;

    // Decimals I keep crypto quantities at.
    public static final int CRYPTO_SCALE = 8;

    private MoneyMath() {
    }

    // How much crypto you get for spending `fiat` at `price`. I floor it to 8 decimals so the
    // buyer never ends up with slightly more than they actually paid for.
    public static BigDecimal buyQuantity(BigDecimal fiat, BigDecimal price) {
        requirePositive(fiat, "fiat");
        requirePositive(price, "price");
        return fiat.divide(price, CRYPTO_SCALE, RoundingMode.DOWN);
    }

    // What `quantity` of a coin is worth at `price`, to the cent. I use this for sell proceeds
    // and for valuing the portfolio.
    public static BigDecimal fiatValue(BigDecimal quantity, BigDecimal price) {
        requireNonNegative(quantity, "quantity");
        requirePositive(price, "price");
        return quantity.multiply(price).setScale(FIAT_SCALE, RoundingMode.HALF_UP);
    }

    // Tidy a crypto quantity to 8 decimals (rounding down).
    public static BigDecimal normalizeQuantity(BigDecimal quantity) {
        requireNonNegative(quantity, "quantity");
        return quantity.setScale(CRYPTO_SCALE, RoundingMode.DOWN);
    }

    // Tidy a fiat amount to 2 decimals (rounding the normal way).
    public static BigDecimal normalizeFiat(BigDecimal fiat) {
        requireNonNegative(fiat, "fiat");
        return fiat.setScale(FIAT_SCALE, RoundingMode.HALF_UP);
    }

    // A price or amount of money being zero or negative is almost always a bug, so I stop early
    // with a clear message rather than letting it flow through.
    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was: " + value);
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative, was: " + value);
        }
    }
}
