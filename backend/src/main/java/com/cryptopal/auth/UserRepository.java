package com.cryptopal.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes users. Spring Data implements this from the method names. */
public interface UserRepository extends JpaRepository<User, UUID> {

    // Callers must pass an already lowercased address. Postgres compares text
    // case-sensitively, so "Kenan@x.com" would simply not find "kenan@x.com".
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
