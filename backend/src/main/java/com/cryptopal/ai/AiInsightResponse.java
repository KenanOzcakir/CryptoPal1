package com.cryptopal.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The assistant's answer, as Markdown.
 *
 * <p>One field, matching API_CONTRACT.md. The frontend renders it through a Markdown
 * component rather than dropping it into the DOM as HTML.
 */
@Schema(description = "The assistant's answer.")
public record AiInsightResponse(

        @Schema(description = "The answer, formatted as Markdown.",
                example = "Your portfolio is worth about **$84,401**, almost all of it in cash.")
        String answer) {
}
