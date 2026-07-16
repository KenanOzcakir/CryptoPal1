package com.cryptopal.auth;

import com.cryptopal.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three account endpoints. Thin on purpose: it validates the shape of what came in,
 * hands it to {@link AuthService}, and returns what comes back. No password or token
 * logic lives here.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Register, log in, and log out.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account",
            description = "Creates a user and a wallet holding a randomized starting balance "
                    + "between 10,000 and 100,000 virtual USD, then returns a session token so "
                    + "the new account is logged in already.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    // @Valid is what makes the annotations on RegisterRequest actually run. Without it
    // they are just documentation, and a blank password would reach the service.
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // 201 rather than 200: this created something, and the status should say so.
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in",
            description = "Exchanges credentials for a session token. The same error comes back "
                    + "for an unknown address and a wrong password, so this endpoint cannot be "
                    + "used to discover which addresses have accounts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Logged in"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out",
            description = "Deletes the session from Redis, so the token stops working immediately "
                    + "rather than lingering until it would have expired.")
    @ApiResponse(responseCode = "204", description = "Logged out")
    public ResponseEntity<Void> logout(
            // Put there by SessionAuthFilter, which has already checked it. Reading the
            // token from the attribute rather than the header keeps the "Bearer " parsing
            // in one place.
            @RequestAttribute(SessionAuthFilter.AUTH_TOKEN_ATTRIBUTE) String token) {
        authService.logout(token);
        return ResponseEntity.noContent().build();
    }
}
