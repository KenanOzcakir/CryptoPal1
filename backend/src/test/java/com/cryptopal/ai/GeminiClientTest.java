package com.cryptopal.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Covers what happens when Gemini misbehaves, which is most of what this client is for.
 * Gemini is the only dependency in this project with no fallback, so every one of these
 * paths has to end in a clean AI_UNAVAILABLE rather than a stack trace.
 *
 * <p>The WebClient is stubbed with a canned response, the same trick
 * BinanceMarketDataProviderTest uses, so none of this touches the network or spends a
 * token.
 */
class GeminiClientTest {

    private static final String KEY = "a-test-key";
    private static final String MODEL = "gemini-3.1-flash-lite";

    @Test
    void returnsTheAnswerText() {
        String body = """
                {
                  "candidates": [
                    {"content": {"parts": [{"text": "Your portfolio is worth **$84,401**."}]},
                     "finishReason": "STOP"}
                  ],
                  "usageMetadata": {"totalTokenCount": 346},
                  "modelVersion": "gemini-3.1-flash-lite"
                }
                """;

        String answer = clientFor(body, HttpStatus.OK).generate("what is my portfolio worth");

        // Note usageMetadata and modelVersion in the body: unknown fields must not break
        // parsing, or a change on Google's side would take the feature down.
        assertThat(answer).isEqualTo("Your portfolio is worth **$84,401**.");
    }

    @Test
    void anUnconfiguredKeyRefusesWithoutCallingGoogle() {
        // .env ships GEMINI_API_KEY=replace_me. Sending that to Google and waiting for the
        // rejection would be the same answer, one round trip slower.
        var client = new GeminiClient(WebClient.builder().build(), "replace_me", MODEL, 20);

        assertThatThrownBy(() -> client.generate("anything"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AI_UNAVAILABLE);
    }

    @Test
    void aBlankKeyRefusesTheSameWay() {
        var client = new GeminiClient(WebClient.builder().build(), "", MODEL, 20);

        assertThatThrownBy(() -> client.generate("anything"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AI_UNAVAILABLE);
    }

    @Test
    void aBusyModelIsReportedAsTemporary() {
        // Not hypothetical: gemini-3.5-flash returned exactly this while segment 6 was
        // being built.
        String body = """
                {"error": {"code": 503, "status": "UNAVAILABLE",
                 "message": "This model is currently experiencing high demand."}}
                """;

        assertThatThrownBy(() -> clientFor(body, HttpStatus.SERVICE_UNAVAILABLE).generate("q"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                    // Tells the user to try again, because trying again genuinely helps.
                    assertThat(e.getMessage()).contains("busy").contains("try again");
                });
    }

    @Test
    void aQuotaFailureIsAlsoReportedAsTemporary() {
        String body = """
                {"error": {"code": 429, "message": "You exceeded your current quota."}}
                """;

        assertThatThrownBy(() -> clientFor(body, HttpStatus.TOO_MANY_REQUESTS).generate("q"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("busy");
    }

    @Test
    void aRejectedKeyNeverExplainsItselfToTheCaller() {
        String body = """
                {"error": {"code": 403, "message": "API key not valid. Please pass a valid API key."}}
                """;

        assertThatThrownBy(() -> clientFor(body, HttpStatus.FORBIDDEN).generate("q"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                    // Whether the server's key is wrong is not the asker's business, and
                    // saying so would tell an attacker the deployment is misconfigured.
                    assertThat(e.getMessage())
                            .doesNotContain("key")
                            .doesNotContain("API")
                            .isEqualTo("The AI assistant is not available right now");
                });
    }

    @Test
    void aBlockedPromptComesBackAsNoAnswerRatherThanACrash() {
        // Safety filters answer HTTP 200 with no candidates at all.
        String body = """
                {"promptFeedback": {"blockReason": "SAFETY"}}
                """;

        assertThatThrownBy(() -> clientFor(body, HttpStatus.OK).generate("q"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("rephrasing");
                });
    }

    @Test
    void anEmptyAnswerIsRefusedRatherThanReturnedAsBlank() {
        String body = """
                {"candidates": [{"content": {"parts": [{"text": "   "}]}, "finishReason": "STOP"}]}
                """;

        // Showing a user an empty chat bubble is worse than telling them it failed.
        assertThatThrownBy(() -> clientFor(body, HttpStatus.OK).generate("q"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aTruncatedAnswerIsStillWorthReturning() {
        String body = """
                {"candidates": [{"content": {"parts": [{"text": "Your portfolio is worth about"}]},
                 "finishReason": "MAX_TOKENS"}]}
                """;

        // Cut short is not the same as missing. Half an answer beats an error.
        assertThat(clientFor(body, HttpStatus.OK).generate("q"))
                .isEqualTo("Your portfolio is worth about");
    }

    /** A GeminiClient whose every call resolves to the given canned response, no network. */
    private GeminiClient clientFor(String body, HttpStatus status) {
        ExchangeFunction exchange = request -> Mono.just(
                ClientResponse.create(status)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build());
        WebClient webClient = WebClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .exchangeFunction(exchange)
                .build();
        return new GeminiClient(webClient, KEY, MODEL, 20);
    }
}
