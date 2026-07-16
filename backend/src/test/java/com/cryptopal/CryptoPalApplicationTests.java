package com.cryptopal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole application once, which is a bigger check than it looks.
 *
 * <p>For the context to start, the datasource has to connect, Flyway has to run every
 * migration cleanly, and Hibernate has to agree that the entities match the tables it
 * finds. Any one of those breaking fails this test, so it catches the kind of wiring
 * mistake that unit tests never see.
 *
 * <p>What it deliberately does not prove is Redis. Spring Data Redis opens its
 * connection lazily, so this test still passes with Redis stopped. Redis is covered by
 * {@code /actuator/health}, which does go DOWN when it is unreachable, and it will be
 * covered here properly once the session code actually reads and writes keys.
 *
 * <p>It needs PostgreSQL running, so start the infrastructure first:
 *
 * <pre>docker compose up -d</pre>
 */
@SpringBootTest
class CryptoPalApplicationTests {

    @Test
    void contextLoads() {
        // Empty on purpose. Starting the context above is the whole assertion, and
        // anything else here would just be testing Spring rather than my application.
    }
}
