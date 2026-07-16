package com.cryptopal.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
        // Spring's own web exceptions land here too, and they are not bugs: an unknown
        // path, a wrong HTTP method, a missing Content-Type. They all implement Spring's
        // ErrorResponse interface and already carry the correct status, so they are
        // translated rather than buried.
        //
        // This check is the whole reason the method is not just a log-and-500. Without
        // it, every mistyped URL answered 500 "something went wrong on our side" and
        // logged a stack trace, so anything scanning for paths would fill the log with
        // errors that were never errors.
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            var status = springError.getStatusCode();
            ErrorCode code = codeForStatus(status);
            log.debug("Framework rejected the request with {}: {}", status, ex.getMessage());
            return ResponseEntity.status(status).body(ErrorResponse.of(code, messageFor(code)));
        }

        // Full detail to the log, deliberately nothing to the caller: stack traces and
        // SQL fragments tell an attacker about the internals, and mean nothing to a user.
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong on our side");
    }

    private static ErrorCode codeForStatus(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        // Anything else the framework called a client error means the request itself was
        // wrong, which is what VALIDATION_ERROR says. A 5xx from the framework is still
        // our problem.
        return status.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
    }

    // Our own wording rather than the framework's. Spring's text for a missing route is
    // "No static resource api/market/prices", which describes its internals rather than
    // anything the caller did.
    private static String messageFor(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> "No endpoint exists at that path";
            case METHOD_NOT_ALLOWED -> "That HTTP method is not allowed on this path";
            case INTERNAL_ERROR -> "Something went wrong on our side";
            default -> "The request could not be processed";
        };
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }
}
