package com.cryptopal.auth;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
// Jackson 3, note the tools.jackson package rather than com.fasterxml.jackson. Spring
// Boot 4 ships Jackson 3 and auto-configures a JsonMapper from this package, so this is
// the ObjectMapper that actually exists as a bean. Jackson 2 is still on the classpath
// underneath, pulled in by other libraries, which makes importing the wrong one very
// easy and the resulting error ("no qualifying bean of type ObjectMapper") misleading.
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Active sessions, held in Redis under {@code session:<token>} with a TTL.
 *
 * <p>The tokens are opaque: random bytes that mean nothing on their own and are only a
 * lookup key into Redis. That is the whole point of choosing them over JWTs. A JWT
 * carries its own claims and is valid until it expires, so a real logout needs a denylist
 * bolted on. Here, logout is a DELETE, and the token is dead the instant it happens.
 *
 * <p>The trade-off, taken knowingly: every authenticated request costs one Redis read.
 * That is exactly what Redis is fast at, and it buys genuine server-side revocation.
 *
 * <p>Redis is the only home for this data. Losing it logs everyone out, which is an
 * inconvenience rather than a loss, and is why the container runs with no persistence.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final String KEY_PREFIX = "session:";

    // 32 bytes, so 256 bits of entropy. Far past anything guessable, and it costs
    // nothing: the token is a 43 character string either way.
    private static final int TOKEN_BYTES = 32;

    // SecureRandom, not Random. A session token is a credential, and java.util.Random is
    // a predictable generator: observing a few outputs is enough to work out the rest.
    private final SecureRandom random = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SessionService(StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          @Value("${SESSION_TTL_MINUTES}") long sessionTtlMinutes) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(sessionTtlMinutes);
    }

    /** Issues a token for a user and stores the session against it. */
    public String createSession(User user) {
        String token = newToken();
        var session = new AuthenticatedUser(user.getId(), user.getEmail());
        // set(key, value, ttl) is a single SETEX. Writing the value and then setting the
        // expiry separately would be two round trips, and a crash between them would
        // leave a session that never expires.
        redis.opsForValue().set(KEY_PREFIX + token, objectMapper.writeValueAsString(session), ttl);
        return token;
    }

    /** Looks a token up. Empty means unknown, expired, or already logged out, and the
     *  caller cannot tell which, which is deliberate. */
    public Optional<AuthenticatedUser> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(KEY_PREFIX + token);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(json));
    }

    /** Ends a session now. Deleting a token that is already gone is not an error, so
     *  logging out twice is harmless. */
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(KEY_PREFIX + token);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        // URL-safe alphabet with no padding, so the token survives being put in a header
        // or a URL without anything needing to escape it.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AuthenticatedUser deserialize(String json) {
        try {
            return objectMapper.readValue(json, AuthenticatedUser.class);
        } catch (JacksonException e) {
            // Only reachable if something else wrote into my key space, or if this record
            // changed shape while sessions in the old shape were still alive. Treating it
            // as "not logged in" is the safe reading: refusing a session I cannot make
            // sense of is better than guessing who it belonged to.
            //
            // Jackson 3 made its exceptions unchecked, so nothing forces this catch. That
            // is exactly why it is worth writing out: without it, an unreadable payload
            // would surface as a 500 rather than a 401.
            log.warn("Discarding unreadable session payload", e);
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Session is invalid or has expired", e);
        }
    }
}
