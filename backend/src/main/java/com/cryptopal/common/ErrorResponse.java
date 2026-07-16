package com.cryptopal.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The single error shape this API returns, for every failure, from every endpoint.
 *
 * <p>One shape everywhere means the frontend writes its error handling once. The field
 * order here is the order Jackson serializes them in, and it matches API_CONTRACT.md.
 *
 * @param message human readable, safe to show a user, and never containing internals
 *                like a stack trace or a SQL fragment
 * @param code    stable machine readable code, the thing the frontend should branch on
 * @param timestamp when the failure happened, in UTC
 */
@Schema(description = "Standard error response returned by every endpoint on failure.")
public record ErrorResponse(

        @Schema(example = "Insufficient funds to complete this trade")
        String message,

        @Schema(example = "INSUFFICIENT_FUNDS")
        ErrorCode code,

        @Schema(example = "2026-07-16T12:00:00Z")
        Instant timestamp) {

    /** Builds a response stamped with the current time, which is the usual case. */
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(message, code, Instant.now());
    }
}
