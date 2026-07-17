package com.cryptopal.ai;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caps how often the assistant can be asked, per user and in total.
 *
 * <p>Gemini is the one dependency here I do not own the capacity of. The key sits in a
 * project with billing switched off, so running out cannot cost me money: it costs me the
 * feature, and it costs it to everyone at once. That is the thing worth protecting. A
 * visitor emptying the day's quota at breakfast would leave the assistant dead when I am
 * demonstrating it in the afternoon.
 *
 * <p>Two counters, because they defend against different things:
 *
 * <ul>
 *   <li><b>Per user</b> is fairness. It stops one person leaning on the button.
 *   <li><b>Global</b> is the actual protection. Registration is open, so a per-user limit
 *       alone means anyone patient enough to register ten accounts has ten times the
 *       allowance. Only a global ceiling bounds the day.
 * </ul>
 *
 * <p>Both counters live in Redis, which is volatile here by design: a restart resets them.
 * That makes this a courtesy limit rather than a guarantee, and I would rather write that
 * down than imply it is ironclad. For a demo it is the right trade, and the durable
 * alternative would mean writing counters to PostgreSQL on the path of every question.
 */
@Component
public class AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimiter.class);

    private static final String USER_KEY_PREFIX = "ai:quota:user:";
    private static final String GLOBAL_KEY = "ai:quota:global";

    /**
     * A rolling day, starting at a caller's first question, rather than a calendar day.
     * The key expires itself, so there is no date arithmetic and no argument about which
     * midnight counts: mine is 3am UTC, and a limit that resets while I sleep is a limit I
     * would have to think about.
     */
    private static final Duration WINDOW = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final int perUserLimit;
    private final int globalLimit;

    public AiRateLimiter(StringRedisTemplate redis,
                         @Value("${AI_DAILY_QUESTION_LIMIT:20}") int perUserLimit,
                         @Value("${AI_DAILY_GLOBAL_LIMIT:300}") int globalLimit) {
        this.redis = redis;
        this.perUserLimit = perUserLimit;
        this.globalLimit = globalLimit;
    }

    /**
     * Counts one question against both budgets and refuses if either is spent.
     *
     * <p>The attempt is counted, not the success. Counting only successes would make a
     * failed call free and infinitely retryable, which is exactly how a quota gets drained:
     * every retry still reaches Gemini. The cost of this choice is real and worth naming:
     * if Gemini is having an outage, a user spends allowance on answers they never got.
     */
    public void recordQuestion(UUID userId) {
        // Per user first. If someone has spent their own allowance, saying so is more
        // useful than telling them the service is busy, and it avoids charging their
        // question against the global budget as well.
        long userCount = increment(USER_KEY_PREFIX + userId);
        if (userCount > perUserLimit) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "You have used your " + perUserLimit + " assistant questions for today. "
                            + "This resets 24 hours after your first question.");
        }

        long globalCount = increment(GLOBAL_KEY);
        if (globalCount > globalLimit) {
            // Worth a warning: either the app is more popular than expected, or somebody is
            // leaning on it. Either way I want to know without reading a graph.
            log.warn("The global assistant limit of {} questions a day has been reached", globalLimit);
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "The assistant has answered as many questions as it can today. "
                            + "Everything else in the app still works.");
        }
    }

    /**
     * Increments a counter and makes sure it expires.
     *
     * <p>INCR is atomic and creates the key at zero if it is missing, so exactly one caller
     * ever sees 1, and that caller is the one that sets the expiry. Setting it on every
     * call instead would push the window forward with each question and turn a daily limit
     * into one that never resets for an active user.
     *
     * <p>The expiry is not optional. Without it a counter would survive forever and quietly
     * lock a user out for good, which is the same class of bug as a price cache with no TTL.
     */
    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis is unreachable. Not a reason to refuse a question: the assistant is
            // allowed to degrade, but it should not be gated by a counter that is not
            // answering. Failing open here is deliberate.
            log.warn("Could not read the assistant quota from Redis, allowing the question through");
            return 0;
        }
        if (count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count;
    }
}
