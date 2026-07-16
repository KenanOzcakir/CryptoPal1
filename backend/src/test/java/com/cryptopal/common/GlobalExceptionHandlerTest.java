package com.cryptopal.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
// Jackson 3, matching what Spring Boot 4 auto-configures and therefore what actually
// serializes these responses at runtime. This started out as a Jackson 2 mapper with a
// JavaTimeModule registered by hand, which passed while testing a library the
// application does not serialize with.
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the error contract itself, since every endpoint in the application depends on
 * it and there are no controllers yet to reach it through.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionAnswersWithTheStatusItsCodeCarries() {
        var ex = new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds to complete this trade");

        ResponseEntity<ErrorResponse> response = handler.handleApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(response.getBody().message()).isEqualTo("Insufficient funds to complete this trade");
    }

    @Test
    void priceUnavailableIsA503SoCallersKnowToRetry() {
        // Worth pinning down: a missing price is temporary, and answering 500 here would
        // tell the frontend the app is broken when the next refresh is seconds away.
        var ex = new ApiException(ErrorCode.PRICE_UNAVAILABLE, "No price for BTC yet");

        assertThat(handler.handleApiException(ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unexpectedExceptionNeverLeaksInternalsToTheCaller() {
        // The kind of message that must not escape: it names internal tables.
        var leaky = new IllegalStateException("ERROR: relation \"users\" does not exist at position 42");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(leaky);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(response.getBody().message())
                .isEqualTo("Something went wrong on our side")
                .doesNotContain("users", "relation", "position");
    }

    @Test
    void serializedShapeMatchesTheApiContract() throws Exception {
        // No time module to register: Jackson 3 handles java.time out of the box.
        var mapper = JsonMapper.builder().build();
        var body = new ErrorResponse("Insufficient funds to complete this trade",
                ErrorCode.INSUFFICIENT_FUNDS, Instant.parse("2026-07-16T12:00:00Z"));

        String json = mapper.writeValueAsString(body);

        // The exact three fields documented in API_CONTRACT.md, in that order, and
        // nothing extra. (Jackson 3 renamed fieldNames() to propertyNames().)
        assertThat(mapper.readTree(json).propertyNames())
                .containsExactly("message", "code", "timestamp");
        assertThat(json).contains("\"code\":\"INSUFFICIENT_FUNDS\"");
    }
}
