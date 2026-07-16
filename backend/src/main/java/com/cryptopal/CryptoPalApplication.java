package com.cryptopal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The entry point of CryptoPal Core. Everything below this package is one deployable
 * Spring Boot application, split into feature modules (common, auth, market, trading,
 * ai) rather than into separate services.
 *
 * <p>Living at the root of com.cryptopal is what makes the module packages work with no
 * extra configuration: component scanning starts here and walks downwards, so every
 * controller, service, and repository in those packages is found automatically.
 */
@SpringBootApplication
// Turns on the scheduler that the market module uses to refresh prices every 15
// seconds. It belongs here rather than on that service because it switches on the
// mechanism for the whole application, not for one class.
@EnableScheduling
public class CryptoPalApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoPalApplication.class, args);
    }
}
