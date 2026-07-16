package com.cryptopal.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Supplies the password hasher.
 *
 * <p>This bean has to be declared by hand because the project depends on
 * spring-security-crypto rather than spring-boot-starter-security, so there is no
 * auto-configuration to provide one. That is the deal that was struck for not having a
 * filter chain locking down every route: one bean definition instead of a pile of
 * configuration undoing defaults.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt at its default strength of 10, which is 2^10 rounds. It is deliberately
        // slow: hashing a password takes a few tens of milliseconds, which nobody logging
        // in will notice, and which makes guessing at scale expensive. It also salts
        // every hash on its own, so two users with the same password get different hashes
        // and a precomputed table is worthless.
        return new BCryptPasswordEncoder();
    }
}
