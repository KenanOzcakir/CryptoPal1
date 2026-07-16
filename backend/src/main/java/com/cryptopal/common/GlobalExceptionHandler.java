package com.cryptopal.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place an exception becomes an HTTP response.
 *
 * <p>Because this is here, controllers and services never build error responses
 * themselves: they throw, and every caller gets the same {@link ErrorResponse} shape no
 * matter which module failed. Without it, Spring's default error body would leak out
 * for anything I forgot to catch, and the frontend would have two formats to handle.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A rule I wrote said no. The code already knows which status to use. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        // Expected outcomes, not faults, so these stay at debug: a user mistyping a
        // password should not fill the log with stack traces.
        log.debug("Rejected with {}: {}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.code().status())
                .body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /** A DTO failed its Bean Validation annotations. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // Report every bad field at once. Fixing one problem only to be told about the
        // next one is a miserable way to fill in a form.
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        return respond(ErrorCode.VALIDATION_ERROR, message);
    }

    /** The body was not readable at all, for example malformed JSON or a string where a
     *  number belongs. Spring would answer 400 on its own, but with its shape, not mine. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return respond(ErrorCode.VALIDATION_ERROR, "Request body is malformed or missing");
    }

    /** The catch-all. Anything reaching here is a bug I did not anticipate. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Full detail to the log, deliberately nothing to the caller: stack traces and
        // SQL fragments tell an attacker about the internals, and mean nothing to a user.
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong on our side");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }
}
