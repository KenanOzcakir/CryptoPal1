package com.cryptopal.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The HTTP client for Gemini.
 *
 * <p>Its own {@link WebClient} rather than a shared one, because it points at a different
 * host to the Binance client and carries a different timeout. Both are built the same way,
 * so {@code MarketConfig} is the sibling worth reading next to this.
 */
@Configuration
public class AiConfig {

    @Bean
    public WebClient geminiWebClient(
            @Value("${GEMINI_BASE_URL:https://generativelanguage.googleapis.com}") String baseUrl) {
        // No API key on the builder. The key is a per-request header set in GeminiClient,
        // so it is not baked into a shared bean and cannot be picked up by anything else
        // that happens to inject this client.
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
