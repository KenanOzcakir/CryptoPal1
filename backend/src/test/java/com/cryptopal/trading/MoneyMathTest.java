package com.cryptopal.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyMathTest {

    @Test
    void buyQuantityFloorsToEightDecimals() {
        // 100 / 3 = 33.3333333... -> floored at 8 dp
        assertEquals(new BigDecimal("33.33333333"),
                MoneyMath.buyQuantity(new BigDecimal("100"), new BigDecimal("3")));
        // 100 / 65000 = 0.00153846... -> floored at 8 dp
        assertEquals(new BigDecimal("0.00153846"),
                MoneyMath.buyQuantity(new BigDecimal("100"), new BigDecimal("65000")));
    }

    @Test
    void buyQuantityNeverCreditsMoreThanPaid() {
        BigDecimal fiat = new BigDecimal("100");
        BigDecimal price = new BigDecimal("3");
        BigDecimal qty = MoneyMath.buyQuantity(fiat, price);
        // qty * price must not exceed the fiat spent (flooring guarantees this)
        assertTrue(qty.multiply(price).compareTo(fiat) <= 0,
                "flooring must never let quantity*price exceed fiat spent");
    }

    @Test
    void buyQuantityRejectsNonPositiveInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMath.buyQuantity(new BigDecimal("0"), new BigDecimal("3")));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMath.buyQuantity(new BigDecimal("100"), new BigDecimal("0")));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMath.buyQuantity(new BigDecimal("-1"), new BigDecimal("3")));
    }

    @Test
    void fiatValueRoundsHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("32500.00"),
                MoneyMath.fiatValue(new BigDecimal("0.5"), new BigDecimal("65000")));
        // 1 * 100.005 = 100.005 -> HALF_UP -> 100.01
        assertEquals(new BigDecimal("100.01"),
                MoneyMath.fiatValue(BigDecimal.ONE, new BigDecimal("100.005")));
    }

    @Test
    void fiatValueAllowsZeroQuantity() {
        assertEquals(new BigDecimal("0.00"),
                MoneyMath.fiatValue(BigDecimal.ZERO, new BigDecimal("65000")));
    }

    @Test
    void normalizersApplyTheRightScaleAndRounding() {
        assertEquals(new BigDecimal("1.12345678"),
                MoneyMath.normalizeQuantity(new BigDecimal("1.123456789")));   // DOWN
        assertEquals(new BigDecimal("10.01"),
                MoneyMath.normalizeFiat(new BigDecimal("10.005")));            // HALF_UP
    }
}
