package com.cryptopal.auth;

import java.util.UUID;

/**
 * Who is making the current request, resolved from a session token.
 *
 * <p>This is what gets stored in Redis under the token and what {@link SessionAuthFilter}
 * attaches to the request once it has checked one. It carries identity only: no password
 * hash, no balance, nothing an endpoint should be reading from a cache instead of from
 * PostgreSQL.
 *
 * <p>The architecture sketch had a separate SessionDto for the Redis side and an
 * AuthenticatedUser for the request side. They would have held the same two fields, so
 * they are one type here, for the same reason DECISIONS.md 5 dropped the Redis
 * repository wrappers: indirection with no behavior behind it.
 */
public record AuthenticatedUser(UUID userId, String email) {
}
