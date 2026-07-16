package com.cryptopal.trading;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * OrderSide is just whether a trade is a BUY or a SELL. I save it on every Transaction and
 * send it back in order responses.
 */
public enum OrderSide {
    BUY,
    SELL;

    // Turn whatever the request sent ("buy", "SELL", " Buy ") into the enum. I trim and ignore
    // case so I'm not fussy about how it comes in, but I still throw on anything that isn't a
    // real side instead of quietly guessing.
    //
    // @JsonCreator points Jackson at this method instead of its own strict, case-sensitive
    // enum matching, so a request body saying "buy" is accepted rather than rejected for
    // its capitalisation. Anything that is not a real side still throws, and the global
    // handler turns that into a VALIDATION_ERROR.
    @JsonCreator
    public static OrderSide fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("order side must not be null");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BUY" -> BUY;
            case "SELL" -> SELL;
            default -> throw new IllegalArgumentException("unknown order side: " + value);
        };
    }
}
