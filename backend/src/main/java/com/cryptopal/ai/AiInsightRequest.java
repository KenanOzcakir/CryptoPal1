package com.cryptopal.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question about the caller's own account. */
@Schema(description = "A question for the assistant about your account or the market.")
public record AiInsightRequest(

        @Schema(description = "The question, in plain language.",
                example = "What is my current portfolio worth?", maxLength = 500)
        @NotBlank(message = "must not be blank")
        // Capped for two reasons: every character is billed and sits in the model's
        // context, and an unbounded field is an open invitation to paste an essay of
        // instructions in and see what happens.
        @Size(max = 500, message = "must be 500 characters or fewer")
        String question) {
}
