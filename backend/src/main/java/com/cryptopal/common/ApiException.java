package com.cryptopal.common;

/**
 * An error I raised on purpose, because a business rule said no.
 *
 * <p>This is how a service reports "insufficient funds" or "unsupported symbol" without
 * knowing anything about HTTP: it throws, the code carries the meaning, and
 * {@link GlobalExceptionHandler} turns it into a response. The alternative, returning
 * result objects up through every layer, would put error plumbing in every signature.
 *
 * <p>It extends RuntimeException so it does not have to be declared everywhere, and so
 * that throwing it inside a transaction rolls that transaction back, which is exactly
 * what should happen when a trade is rejected midway.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
