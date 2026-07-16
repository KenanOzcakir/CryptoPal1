package com.cryptopal.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered account. Only ever holds the BCrypt hash, never the password itself.
 *
 * <p>Email is the login identity and is stored already lowercased, because Postgres
 * compares text case-sensitively: without normalizing first, Kenan@x.com and kenan@x.com
 * would slip past the unique index as two separate accounts. {@link AuthService} is what
 * lowercases it, so that rule lives in one place.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    // Hibernate generates the UUID rather than letting Postgres' gen_random_uuid()
    // default fire. That way the id exists in memory the moment the object is saved,
    // so the wallet can reference it without a second round trip to read it back.
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application code, which should use the other constructor. */
    protected User() {
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // No toString() on purpose. The default one would print the hash, and the easiest
    // way to leak a credential into a log file is to make it convenient to print.
}
