package com.cryptopal.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The assistant's quota, against the real Redis.
 *
 * <p>Deliberately not mocked. The whole mechanism is INCR plus an expiry, so a mocked
 * Redis would only be testing that I can stub a mock. What is worth proving is that the
 * counting is real, that the two budgets are independent, and that the keys expire, since
 * a counter without a TTL would lock a user out permanently.
 *
 * <p>The limits are turned down to something small here so the tests stay fast and read
 * clearly. That is also a small proof in itself: the numbers come from configuration
 * rather than being baked into the code.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "AI_DAILY_QUESTION_LIMIT=3",
        "AI_DAILY_GLOBAL_LIMIT=5"
})
class AiRateLimiterTest {

    @Autowired
    private AiRateLimiter limiter;
    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void clearQuotaKeys() {
        // Redis is shared with the running app in development, so only this feature's keys
        // are removed. Never flushAll: that would log out anyone using the app.
        var keys = redis.keys("ai:quota:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void aUserGetsExactlyTheirAllowanceAndNoMore() {
        UUID user = UUID.randomUUID();

        for (int i = 1; i <= 3; i++) {
            int question = i;
            assertThatCode(() -> limiter.recordQuestion(user))
                    .as("question %d of the 3 allowed", question)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.recordQuestion(user))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void oneUserRunningOutDoesNotAffectAnyoneElse() {
        UUID heavy = UUID.randomUUID();
        UUID innocent = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            limiter.recordQuestion(heavy);
        }
        assertThatThrownBy(() -> limiter.recordQuestion(heavy)).isInstanceOf(ApiException.class);

        // The whole point of a per-user counter: being unlucky enough to share an app with
        // someone impatient must not cost me my own questions.
        assertThatCode(() -> limiter.recordQuestion(innocent)).doesNotThrowAnyException();
    }

    @Test
    void theGlobalCapStopsEveryoneOnceTheDayIsSpent() {
        // Two users, each well inside their own allowance of 3, but together they cross the
        // global ceiling of 5. This is the case a per-user limit alone cannot catch, and it
        // is why the global counter exists: registration is open, so an attacker with ten
        // accounts would otherwise have ten times the budget.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            limiter.recordQuestion(first);
        }
        for (int i = 0; i < 2; i++) {
            limiter.recordQuestion(second);
        }

        // A fresh user, with a completely untouched personal allowance.
        UUID unlucky = UUID.randomUUID();
        assertThatThrownBy(() -> limiter.recordQuestion(unlucky))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void theCountersExpireSoNobodyIsLockedOutForever() {
        UUID user = UUID.randomUUID();
        limiter.recordQuestion(user);

        // The TTL is the difference between a daily limit and a permanent ban. Remove the
        // expire call in AiRateLimiter and this fails: the key comes back with -1, meaning
        // it lives forever, and that user never gets another question as long as Redis is
        // up. Same class of bug as a price cache with no TTL.
        Long userTtl = redis.getExpire("ai:quota:user:" + user);
        Long globalTtl = redis.getExpire("ai:quota:global");

        assertThat(userTtl).as("per user counter TTL in seconds").isGreaterThan(0);
        assertThat(globalTtl).as("global counter TTL in seconds").isGreaterThan(0);
        assertThat(userTtl).isLessThanOrEqualTo(24 * 60 * 60);
    }

    @Test
    void askingAgainDoesNotPushTheWindowForward() {
        UUID user = UUID.randomUUID();
        String key = "ai:quota:user:" + user;

        limiter.recordQuestion(user);

        // Wind the clock on by hand: pretend this user's 24 hours is nearly up, with ten
        // seconds to go. Without this the test cannot fail, because both questions land in
        // the same second and the TTL reads 86400 either way. My first attempt at this test
        // did exactly that and passed against a deliberately broken implementation.
        redis.expire(key, Duration.ofSeconds(10));

        limiter.recordQuestion(user);

        // Still counting down from ten, not reset to a fresh day. The expiry is set once,
        // on the first question, and never touched again. Setting it on every call would
        // slide the window forward with each question, so an active user's allowance would
        // never reset and a daily limit would quietly become a lifetime one.
        assertThat(redis.getExpire(key))
                .as("TTL after a second question, when ten seconds were left")
                .isLessThanOrEqualTo(10);
    }
}
