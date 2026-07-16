package com.cryptopal.common;

import org.springframework.http.HttpStatus;

/**
 * Every error this API can return, and the HTTP status that goes with it.
 *
 * <p>Pairing the code with its status here means a caller cannot invent a new one in
 * passing, and the frontend can switch on a stable string instead of parsing English
 * prose that I might reword later.
 */
public enum ErrorCode {

    /** Request body failed validation, for example a blank email or a negative amount. */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    /** Missing, expired, or unknown session token. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** Registration attempted with an email that is already taken. 409, because the
     *  request is well formed and only conflicts with state that already exists. */
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),

    /** Buy order costs more than the wallet holds. */
    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST),

    /** Sell order is for more of an asset than the user actually owns. */
    INSUFFICIENT_HOLDINGS(HttpStatus.BAD_REQUEST),

    /** Symbol is not one of the assets this application trades. */
    UNSUPPORTED_SYMBOL(HttpStatus.BAD_REQUEST),

    /** No cached price for the symbol yet, so a trade or valuation would be guesswork.
     *  503 rather than 500: nothing is broken, the price is simply not there yet and
     *  the next refresh is seconds away, so retrying is the right response. */
    PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    /** Gemini timed out, rate limited, rejected the key, or returned nothing usable. */
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    /** Anything unplanned. The details go to the log, never to the caller. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
