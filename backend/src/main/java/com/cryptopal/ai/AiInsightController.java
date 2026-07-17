package com.cryptopal.ai;

import com.cryptopal.auth.AuthenticatedUser;
import com.cryptopal.auth.SessionAuthFilter;
import com.cryptopal.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant endpoint. Requires a session.
 *
 * <p>The account is taken from the session token, never from the request, so a question
 * can only ever be answered about the asker's own account. There is no user id to tamper
 * with because there is no user id in the request.
 */
@RestController
@Tag(name = "AI", description = "Ask about your account and recent market activity. Session required.")
public class AiInsightController {

    private final AiInsightService aiInsightService;

    public AiInsightController(AiInsightService aiInsightService) {
        this.aiInsightService = aiInsightService;
    }

    @PostMapping("/api/ai/ask")
    @Operation(summary = "Ask the assistant about your account",
            description = """
                    Answers questions such as "what is my portfolio worth", "what did I trade \
                    recently", or "how has BTC moved". The answer comes back as Markdown.

                    Gemini is called only from the server and is given only this account's \
                    own balance, holdings, recent trades, and current prices. It is told to \
                    use nothing else, and every figure it is given is already calculated, so \
                    it is describing numbers rather than working them out.

                    **This is the only endpoint with a quota**, because it is the only one \
                    that spends capacity I do not own. Two limits apply, and crossing either \
                    answers `RATE_LIMITED` (429):

                    - **20 questions per account**, so one person cannot use the whole day.
                    - **300 questions in total**, across everyone. This is the real ceiling: \
                    registration is open, so a per-account limit alone would mean ten \
                    accounts get ten times the allowance.

                    Both are rolling 24 hour windows that start at your first question, not \
                    calendar days, so there is no particular midnight to wait for. The \
                    attempt is counted rather than the answer, so a question the assistant \
                    fails to answer still spends one. Nothing else in the API is rate \
                    limited, and nothing else stops working when this runs out.

                    Answers are informational, not financial advice, and the account is \
                    simulated.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The answer, as Markdown"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR, blank or over 500 characters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "RATE_LIMITED. Either this account's 20 questions or the "
                            + "shared daily 300 are spent. The message says which, and when "
                            + "it resets. Retrying later works; retrying now does not",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "AI_UNAVAILABLE (assistant unreachable, busy, or unconfigured) "
                            + "or PRICE_UNAVAILABLE (no prices to build the context from)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AiInsightResponse ask(
            @Valid @RequestBody AiInsightRequest request,
            @RequestAttribute(SessionAuthFilter.AUTH_USER_ATTRIBUTE) AuthenticatedUser user) {
        return aiInsightService.ask(user.userId(), request.question());
    }
}
