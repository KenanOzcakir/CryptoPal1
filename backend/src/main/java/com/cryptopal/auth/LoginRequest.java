package com.cryptopal.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * A login attempt.
 *
 * <p>Only checked for being present, deliberately. Applying the registration rules here
 * (valid email format, 8 character minimum) would answer "that is not even a valid
 * password" before checking anything, which tells someone probing the API what the rules
 * are and, worse, distinguishes a malformed guess from a wrong one. Every failure here
 * should look identical from outside.
 */
@Schema(description = "Login with an existing account.")
public record LoginRequest(

        @Schema(example = "user@example.com")
        @NotBlank(message = "must not be blank")
        String email,

        @Schema(example = "StrongPassword123")
        @NotBlank(message = "must not be blank")
        String password) {
}
