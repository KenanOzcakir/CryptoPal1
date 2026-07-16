package com.cryptopal.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new account request. Validated before it reaches {@link AuthService}, so the service
 * can assume the shape is already sane and only worry about the rules that need the
 * database.
 */
@Schema(description = "New account registration.")
public record RegisterRequest(

        @Schema(example = "user@example.com")
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        String email,

        // The 72 byte ceiling is BCrypt's, not mine. BCrypt only reads the first 72
        // bytes, so without this a longer password would be silently truncated and two
        // different passwords sharing a 72 byte prefix would both unlock the account.
        // Rejecting it is honest; quietly cutting it off is not.
        @Schema(example = "StrongPassword123", minLength = 8, maxLength = 72)
        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password) {
}
