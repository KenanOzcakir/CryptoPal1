package com.cryptopal.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * What register and login both return: a session token, and who it belongs to.
 *
 * <p>Registration answers with a token too, so a new user is logged in already rather
 * than being sent to a login form to retype what they just typed.
 */
@Schema(description = "A session token and the account it belongs to.")
public record AuthResponse(

        @Schema(description = "Send as: Authorization: Bearer <token>",
                example = "kQ7Xw2vN8pR4tY6uI0oP1aS3dF5gH7jK9lZ2xC4vB6n")
        String token,

        UserDto user) {

    /** The account, trimmed to what a browser has any business knowing. */
    @Schema(description = "The authenticated account.")
    public record UserDto(
            @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID id,
            @Schema(example = "user@example.com") String email) {
    }

    public static AuthResponse of(String token, User user) {
        return new AuthResponse(token, new UserDto(user.getId(), user.getEmail()));
    }
}
