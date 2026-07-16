package com.cryptopal.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopal.auth.AuthService;
import com.cryptopal.auth.RegisterRequest;
import com.cryptopal.auth.UserRepository;
import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.cryptopal.market.MarketDataService;
import com.cryptopal.market.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives POST /api/ai/ask through the real filter chain.
 *
 * <p>Gemini is mocked, so this suite never calls Google and never spends a token. What is
 * being tested here is our half: that the endpoint is protected, that the context handed
 * to the model is this user's own account, and that a failing model is a clean 503 rather
 * than a broken page.
 *
 * <p>MarketDataService is mocked too, which also keeps its 15 second scheduler from
 * writing rows into the real price history while the suite runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiIntegrationTest {

    private static final String TEST_DOMAIN = "@aitest.local";

    @MockitoBean
    private GeminiClient geminiClient;
    @MockitoBean
    private MarketDataService marketData;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository users;

    private String token;
    private String email;

    @BeforeEach
    void setUp() {
        doReturn(List.of(new PriceQuote("BTC", new BigDecimal("64000.00000000"), Instant.now())))
                .when(marketData).getLatestPrices();
        doReturn(new PriceQuote("BTC", new BigDecimal("64000.00000000"), Instant.now()))
                .when(marketData).getLatestPrice(anyString());

        email = "u" + UUID.randomUUID().toString().substring(0, 8) + TEST_DOMAIN;
        token = authService.register(new RegisterRequest(email, "StrongPassword123")).token();
    }

    @AfterEach
    void removeWhatTheTestCreated() {
        users.findAll().stream()
                .filter(user -> user.getEmail().endsWith(TEST_DOMAIN))
                .forEach(users::delete);
    }

    @Test
    void askingReturnsTheAnswerAsMarkdown() throws Exception {
        doReturn("Your portfolio is worth **$50,000**.").when(geminiClient).generate(anyString());

        mvc.perform(ask("What is my portfolio worth?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Your portfolio is worth **$50,000**."));
    }

    @Test
    void theModelIsGivenThisUsersOwnAccountAndNothingElse() throws Exception {
        doReturn("ok").when(geminiClient).generate(anyString());

        mvc.perform(ask("What is my balance?")).andExpect(status().isOk());

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(geminiClient).generate(prompt.capture());
        String sent = prompt.getValue();

        // The account really was gathered and handed over.
        assertThat(sent).contains("Cash balance:").contains("USER QUESTION").contains("What is my balance?");
        // And nothing that should never leave the server went with it. The session token
        // in particular is a live credential.
        assertThat(sent).doesNotContain(token);
        assertThat(sent).doesNotContain("$2a$");
        assertThat(sent).doesNotContain("password");
    }

    @Test
    void aFailingModelIsACleanServiceUnavailable() throws Exception {
        doThrow(new ApiException(ErrorCode.AI_UNAVAILABLE,
                "The AI assistant is busy right now, please try again in a moment"))
                .when(geminiClient).generate(anyString());

        // 503 and the documented shape, not a stack trace and not a 500.
        mvc.perform(ask("anything"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "The AI assistant is busy right now, please try again in a moment"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void anUnexpectedFailureInsideTheModelClientStillDoesNotLeak() throws Exception {
        // Something the client did not anticipate. The global handler must still keep the
        // internals off the wire.
        doThrow(new IllegalStateException("connection pool exhausted at com.internal.Thing"))
                .when(geminiClient).generate(anyString());

        mvc.perform(ask("anything"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Something went wrong on our side"));
    }

    @Test
    void theEndpointRequiresASession() throws Exception {
        mvc.perform(post("/api/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AiInsightRequest("What is my balance?"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aBlankQuestionIsRejected() throws Exception {
        mvc.perform(ask("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anOversizedQuestionIsRejectedBeforeItReachesTheModel() throws Exception {
        // Every character is billed and sits in the model's context, so the cap is
        // enforced here rather than discovered on the invoice.
        mvc.perform(ask("x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("question")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ask(String question)
            throws Exception {
        return post("/api/ai/ask")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AiInsightRequest(question)));
    }
}
