package com.cryptopal.auth;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.cryptopal.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
// Jackson 3. Spring Boot 4 auto-configures its mapper from tools.jackson, so the
// com.fasterxml.jackson ObjectMapper that is also on the classpath has no bean.
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the session token on protected routes before a controller ever runs.
 *
 * <p>It lives in auth, not common, even though the architecture sketch put it there. Auth
 * is what issues sessions, so auth is what should judge them, which is the same rule that
 * put Wallet in this package. It also keeps common free of dependencies on feature
 * modules, so the arrows only ever point one way.
 *
 * <p>Rejections are written here by hand rather than thrown. A filter runs outside the
 * DispatcherServlet, so an exception from here never reaches
 * {@code GlobalExceptionHandler}, and Spring's default error page would come back
 * instead of the documented error shape.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Where the resolved caller is left for controllers to pick up with @RequestAttribute. */
    public static final String AUTH_USER_ATTRIBUTE = "cryptopal.authUser";

    /** The raw token, kept so logout can delete the exact key without re-parsing the header. */
    public static final String AUTH_TOKEN_ATTRIBUTE = "cryptopal.authToken";

    private static final String BEARER_PREFIX = "Bearer ";

    // Everything under /api/ needs a session unless it is named here. A deny-by-default
    // list is the only kind worth having: forgetting to add a route to an allow-list
    // makes it inaccessible and someone complains, whereas forgetting to add it to a
    // deny-list quietly publishes it.
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login");

    // Market prices are public: they are the same real exchange rates anyone can read
    // from Binance directly, so putting them behind a login protects nothing and would
    // stop the landing page showing anything useful.
    private static final String PUBLIC_MARKET_PREFIX = "/api/market";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public SessionAuthFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Anything that is not the API: actuator, Swagger, the SPA's own files. None of
        // it is user data.
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        // Exact match or a genuine sub-path. A bare startsWith would also wave through
        // "/api/marketing-secrets".
        return path.equals(PUBLIC_MARKET_PREFIX) || path.startsWith(PUBLIC_MARKET_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            reject(response, "Missing or malformed Authorization header");
            return;
        }

        AuthenticatedUser user;
        try {
            user = authService.validateToken(token);
        } catch (ApiException e) {
            reject(response, e.getMessage());
            return;
        }

        request.setAttribute(AUTH_USER_ATTRIBUTE, user);
        request.setAttribute(AUTH_TOKEN_ATTRIBUTE, token);
        filterChain.doFilter(request, response);
    }

    /** Pulls the token out of "Bearer <token>", or null if the header is not that shape. */
    private static String extractToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.UNAUTHORIZED, message));
    }
}
