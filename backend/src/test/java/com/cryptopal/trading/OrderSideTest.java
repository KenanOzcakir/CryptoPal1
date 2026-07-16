package com.cryptopal.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderSideTest {

    @Test
    void parsesCaseInsensitivelyAndTrims() {
        assertEquals(OrderSide.BUY, OrderSide.fromString("buy"));
        assertEquals(OrderSide.BUY, OrderSide.fromString(" Buy "));
        assertEquals(OrderSide.SELL, OrderSide.fromString("SELL"));
        assertEquals(OrderSide.SELL, OrderSide.fromString("sell"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> OrderSide.fromString(null));
    }

    @Test
    void rejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> OrderSide.fromString("hold"));
        assertThrows(IllegalArgumentException.class, () -> OrderSide.fromString(""));
    }
}
