package com.cryptopal.ai;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Talks to Google Gemini, and is the only class that does.
 *
 * <p>The key is read from the environment and never leaves this object: it is not logged,
 * not put in a URL, and not included in any error that reaches a caller. That is a
 * requirement rather than a preference, and it is why the frontend asks the backend
 * instead of calling Gemini itself.
 *
 * <p>Everything that can go wrong here comes back as one clean {@code AI_UNAVAILABLE}
 * rather than as a stack trace: timeout, rate limit, rejected key, network failure, an
 * empty answer, or a reply blocked by Google's safety filters. Gemini is the only
 * dependency in this application with no fallback, so it is also the one most likely to
 * embarrass a live demo, and it must fail politely.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** The value shipped in .env.example. Treated as "not configured". */
    private static final String PLACEHOLDER_KEY = "replace_me";

    // Low temperature on purpose. This assistant reports on an account, so it should read
    // the numbers it was given rather than write interesting prose around them.
    private static final double TEMPERATURE = 0.2;

    // Enough for a few paragraphs of Markdown. A cap matters: it bounds both the cost and
    // how long a user waits.
    private static final int MAX_OUTPUT_TOKENS = 800;

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public GeminiClient(WebClient geminiWebClient,
                        @Value("${GEMINI_API_KEY:}") String apiKey,
                        // Pinned to a specific model rather than the gemini-flash-latest
                        // alias, for the same reason Postgres is pinned to 17 rather than
                        // :latest: a moving target can change behaviour under a project
                        // that is being graded. Chosen by measurement against this API:
                        // flash-lite answered a realistic prompt in 1.0s with no thinking
                        // tokens, where gemini-flash-latest took 7.1s and gemini-3.5-flash
                        // took 16.9s and was returning 503 "high demand" on the free tier.
                        // Override with GEMINI_MODEL if a better one appears.
                        @Value("${GEMINI_MODEL:gemini-3.1-flash-lite}") String model,
                        @Value("${GEMINI_TIMEOUT_SECONDS:20}") long timeoutSeconds) {
        this.webClient = geminiWebClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);

        if (!isConfigured()) {
            // A warning, not a failure. The rest of the application works perfectly well
            // without Gemini, and refusing to start over an unset optional key would make
            // the whole app hostage to one feature.
            log.warn("GEMINI_API_KEY is not set, so /api/ai/ask will answer AI_UNAVAILABLE. "
                    + "Put a real key in .env to enable it.");
        }
    }

    /** Sends a prompt and returns the answer text. */
    public String generate(String prompt) {
        if (!isConfigured()) {
            // Refuse locally rather than sending "replace_me" to Google and waiting for the
            // 400 to come back. Same answer, no round trip, and nothing leaks.
            throw unavailable("The AI assistant is not configured on this server", null);
        }

        GeminiResponse response;
        try {
            response = webClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    // The key goes in a header, not a query parameter. Query strings end up
                    // in access logs, proxy logs, and browser history.
                    .header("x-goog-api-key", apiKey)
                    .bodyValue(GeminiRequest.of(prompt))
                    .retrieve()
                    .bodyToMono(GeminiResponse.class)
                    .block(timeout);
        } catch (WebClientResponseException e) {
            // Log the status, never the body: Gemini echoes parts of the request back in
            // its errors, and the request is the user's account data.
            log.warn("Gemini refused the request with HTTP {}", e.getStatusCode().value());
            throw unavailable(messageForStatus(e.getStatusCode().value()), e);
        } catch (RuntimeException e) {
            // Timeout, DNS, connection reset, anything else. block() wraps a timeout in an
            // IllegalStateException, which is why this catches broadly rather than by type.
            log.warn("Gemini call failed: {}", e.getMessage());
            throw unavailable("The AI assistant did not respond in time, please try again", e);
        }

        return textOf(response);
    }

    private boolean isConfigured() {
        return !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    /** Pulls the answer out, or refuses if there isn't one. */
    private String textOf(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            // Happens when a prompt trips a safety filter: HTTP 200, no candidates.
            throw unavailable("The AI assistant had no answer for that, please try rephrasing", null);
        }
        GeminiResponse.Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            throw unavailable("The AI assistant returned an empty answer, please try again", null);
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw unavailable("The AI assistant returned an empty answer, please try again", null);
        }
        if ("MAX_TOKENS".equals(candidate.finishReason())) {
            // Truncated rather than missing, so the answer is still worth returning.
            log.debug("Gemini answer hit the token cap and was cut short");
        }
        return text.trim();
    }

    // Wording the user sees. Deliberately vague about which of these it was: whether a key
    // is invalid or a quota is exhausted is our problem, not something to explain to
    // whoever is asking about their portfolio.
    private static String messageForStatus(int status) {
        return switch (status) {
            // 429 is the quota running out, 503 is Google's own capacity. Both are
            // temporary and both are worth retrying, so they get the same honest answer.
            // 503 is not hypothetical: gemini-3.5-flash returned "this model is currently
            // experiencing high demand" repeatedly while this was being built.
            case 429, 503 -> "The AI assistant is busy right now, please try again in a moment";
            // A rejected key, a malformed request, a disabled API. All of them mean the
            // server is misconfigured, which is not the asker's problem to debug.
            case 400, 401, 403, 404 -> "The AI assistant is not available right now";
            default -> "The AI assistant is not available right now";
        };
    }

    private static ApiException unavailable(String message, Throwable cause) {
        return cause == null
                ? new ApiException(ErrorCode.AI_UNAVAILABLE, message)
                : new ApiException(ErrorCode.AI_UNAVAILABLE, message, cause);
    }

    // ---------- the wire shapes ----------
    // Nested because nothing outside this class has any business knowing what Gemini's
    // JSON looks like. Swapping to another provider should touch only this file.

    record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

        static GeminiRequest of(String prompt) {
            return new GeminiRequest(
                    List.of(new Content(List.of(new Part(prompt)))),
                    new GenerationConfig(TEMPERATURE, MAX_OUTPUT_TOKENS));
        }

        record Content(List<Part> parts) {
        }

        record Part(String text) {
        }

        record GenerationConfig(double temperature, int maxOutputTokens) {
        }
    }

    // ignoreUnknown because Gemini returns plenty this does not care about (token counts,
    // safety ratings, model version), and adding a field to its response should not break
    // this application.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Candidate(Content content, String finishReason) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Content(List<Part> parts) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Part(String text) {
        }
    }
}
