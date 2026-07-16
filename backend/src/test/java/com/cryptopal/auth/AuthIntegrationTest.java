package com.cryptopal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 repackaged the test auto-configuration alongside its per-technology
// modules, so this is no longer boot.test.autoconfigure.web.servlet.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
// The Jackson 3 mapper, which is the bean Spring Boot 4 actually publishes and the one
// the application serializes responses with. Asserting against a hand-built Jackson 2
// mapper here would be testing a different library than the one under test.
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the auth endpoints through the real filter chain, against real PostgreSQL and
 * real Redis. Needs the infrastructure up: {@code docker compose up -d}.
 *
 * <p>Redis is checked directly here rather than taken on trust. The context test in
 * CryptoPalApplicationTests deliberately does not prove Redis works, because Spring Data
 * Redis connects lazily and that test passes with Redis stopped. These do not.
 *
 * <p>Accounts are created with unique addresses on a dedicated domain and removed
 * afterwards, so runs do not collide and the development database stays clean.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    private static final String TEST_DOMAIN = "@authtest.local";
    private static final String GOOD_PASSWORD = "StrongPassword123";
    private static final String SESSION_KEY_PREFIX = "session:";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserRepository users;
    @Autowired
    private WalletRepository wallets;
    @Autowired
    private StringRedisTemplate redis;

    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestCreated() {
        users.findAll().stream()
                .filter(user -> user.getEmail().endsWith(TEST_DOMAIN))
                .forEach(users::delete);
        issuedTokens.forEach(token -> redis.delete(SESSION_KEY_PREFIX + token));
    }

    // ---------- registration ----------

    @Test
    void registeringReturnsATokenAndTheAccount() throws Exception {
        String email = uniqueEmail();

        AuthResponse response = register(email, GOOD_PASSWORD);

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().email()).isEqualTo(email);
        assertThat(response.user().id()).isNotNull();
    }

    @Test
    void registeringCreatesAWalletWithinTheStartingBalanceRange() throws Exception {
        AuthResponse response = register(uniqueEmail(), GOOD_PASSWORD);

        Wallet wallet = wallets.findByUserId(response.user().id()).orElseThrow();
        assertThat(wallet.getFiatBalance())
                .isGreaterThanOrEqualTo(new BigDecimal("10000.00"))
                .isLessThanOrEqualTo(new BigDecimal("100000.00"));
        // Cents, because the column is numeric(20,2) and money is not a float.
        assertThat(wallet.getFiatBalance().scale()).isEqualTo(2);
    }

    @Test
    void registeringStoresAHashAndNeverThePassword() throws Exception {
        String email = uniqueEmail();

        register(email, GOOD_PASSWORD);

        String stored = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(stored)
                .isNotEqualTo(GOOD_PASSWORD)
                .doesNotContain(GOOD_PASSWORD)
                // BCrypt hashes announce themselves with a $2 version prefix.
                .startsWith("$2");
    }

    @Test
    void aSecondRegistrationWithTheSameEmailIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, GOOD_PASSWORD);

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterRequest(email, GOOD_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void shoutingTheEmailDoesNotBuyASecondAccount() throws Exception {
        String email = uniqueEmail();
        register(email, GOOD_PASSWORD);

        // Same address in capitals. Postgres compares text case-sensitively, so without
        // normalizing in AuthService this would slip past the unique index.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new RegisterRequest(email.toUpperCase(Locale.ROOT), GOOD_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void aTooShortPasswordIsRejectedBeforeItReachesTheService() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterRequest(uniqueEmail(), "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("password")));
    }

    // ---------- login ----------

    @Test
    void loggingInWithTheRightPasswordReturnsAToken() throws Exception {
        String email = uniqueEmail();
        register(email, GOOD_PASSWORD);

        AuthResponse response = login(email, GOOD_PASSWORD);

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().email()).isEqualTo(email);
    }

    @Test
    void loggingInIssuesASessionSeparateFromRegistration() throws Exception {
        String email = uniqueEmail();
        AuthResponse fromRegister = register(email, GOOD_PASSWORD);

        AuthResponse fromLogin = login(email, GOOD_PASSWORD);

        // Two logins are two sessions. Logging out of one must not log out the other,
        // which is what makes "log out everywhere" a deliberate feature rather than the
        // accidental default.
        assertThat(fromLogin.token()).isNotEqualTo(fromRegister.token());
        assertThat(redis.hasKey(SESSION_KEY_PREFIX + fromRegister.token())).isTrue();
        assertThat(redis.hasKey(SESSION_KEY_PREFIX + fromLogin.token())).isTrue();
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email, GOOD_PASSWORD);

        String wrongPassword = failedLoginBody(email, "NotTheRightPassword1");
        String noSuchAccount = failedLoginBody(uniqueEmail(), GOOD_PASSWORD);

        // If these differed, this endpoint would answer "does this person have an
        // account here", which is not a question it should be willing to answer.
        assertThat(wrongPassword).isEqualTo(noSuchAccount);
    }

    // ---------- sessions in redis ----------

    @Test
    void theSessionActuallyLivesInRedisAndCarriesATtl() throws Exception {
        AuthResponse response = register(uniqueEmail(), GOOD_PASSWORD);
        String key = SESSION_KEY_PREFIX + response.token();

        assertThat(redis.hasKey(key)).isTrue();

        Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
        // Positive, and no longer than the configured 120 minutes. A key with no expiry
        // reports -1 here, which is exactly the bug the single SETEX in SessionService
        // exists to prevent.
        assertThat(ttlSeconds).isNotNull().isPositive()
                .isLessThanOrEqualTo(TimeUnit.MINUTES.toSeconds(120));
    }

    @Test
    void theStoredSessionIdentifiesTheUserAndNothingElse() throws Exception {
        String email = uniqueEmail();
        AuthResponse response = register(email, GOOD_PASSWORD);

        String stored = redis.opsForValue().get(SESSION_KEY_PREFIX + response.token());

        assertThat(stored).isNotNull();
        var session = json.readValue(stored, AuthenticatedUser.class);
        assertThat(session.userId()).isEqualTo(response.user().id());
        assertThat(session.email()).isEqualTo(email);
        // No password hash, no balance. Redis holds identity, PostgreSQL holds the money.
        assertThat(stored).doesNotContain("passwordHash", "$2", "fiatBalance");
    }

    // ---------- the filter ----------

    @Test
    void aProtectedRouteWithNoTokenIsRefusedInTheDocumentedShape() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                // The filter runs outside the DispatcherServlet, so it has to write this
                // shape itself. This is what proves it does, rather than letting Spring's
                // default error body escape.
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void aMadeUpTokenIsRefused() throws Exception {
        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer definitely-not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aTokenWithoutTheBearerPrefixIsRefused() throws Exception {
        AuthResponse response = register(uniqueEmail(), GOOD_PASSWORD);

        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, response.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerAndLoginStayReachableWithoutAToken() throws Exception {
        // Guards the public path list: if these ever started requiring a session, nobody
        // could ever get one, and the whole app would be locked out.
        String email = uniqueEmail();
        register(email, GOOD_PASSWORD);
        login(email, GOOD_PASSWORD);
    }

    @Test
    void anUnknownPathBelowAPublicOneIsA404AndNotA500() throws Exception {
        // A regression guard. The catch-all handler used to swallow Spring's
        // NoResourceFoundException and answer 500 "something went wrong on our side",
        // logging a stack trace for what is only a typo. Anything probing for paths
        // would have filled the log with errors that were never errors.
        //
        // The path has to be a public one to reach the 404 at all, since the filter
        // stops everything else first. See the test below.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/market/no-such-thing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                // Our wording, not the framework's. Spring says "No static resource ...",
                // which describes its internals rather than anything the caller did.
                .andExpect(jsonPath("$.message").value("No endpoint exists at that path"));
    }

    @Test
    void anUnknownProtectedPathIsRefusedBeforeItIsFound() throws Exception {
        // 401 rather than 404, and deliberately so. The filter denies everything under
        // /api/ that is not on the public list, so it answers before the router ever
        // decides whether the path exists. That means an anonymous caller cannot tell a
        // real endpoint from an imaginary one, and so cannot map the API by probing.
        mvc.perform(post("/api/there-is-nothing-here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void theWrongHttpMethodIsA405AndNotA500() throws Exception {
        // /api/auth/login exists, but only for POST. Same class of bug as above.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---------- logout ----------

    @Test
    void loggingOutKillsTheTokenImmediately() throws Exception {
        AuthResponse response = register(uniqueEmail(), GOOD_PASSWORD);
        String key = SESSION_KEY_PREFIX + response.token();
        assertThat(redis.hasKey(key)).isTrue();

        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + response.token()))
                .andExpect(status().isNoContent());

        // Gone from Redis, and refused on the next attempt. This is the entire argument
        // for opaque tokens over JWTs: a JWT would still be valid here, because nothing
        // but its own expiry decides that.
        assertThat(redis.hasKey(key)).isFalse();
        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + response.token()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + TEST_DOMAIN;
    }

    private AuthResponse register(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterRequest(email, password))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return remember(json.readValue(body, AuthResponse.class));
    }

    private AuthResponse login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return remember(json.readValue(body, AuthResponse.class));
    }

    /** The code and message of a failed login, without the timestamp, which always differs. */
    private String failedLoginBody(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        return node.get("code").asText() + " / " + node.get("message").asText();
    }

    private AuthResponse remember(AuthResponse response) {
        issuedTokens.add(response.token());
        return response;
    }
}
