/**
 * Registration, login, logout, and sessions.
 *
 * <p>This module owns {@code User} and {@code Wallet}, which is why the wallet entity
 * and its repository live here rather than in trading: registration is what creates a
 * wallet and gives it its randomized starting balance, so the wallet's life begins in
 * this module. Trading imports these types to move money; it does not own them.
 *
 * <p>Sessions are opaque random tokens held in Redis under {@code session:<token>} with
 * a TTL, not JWTs, so that a logout genuinely invalidates a token server-side.
 */
package com.cryptopal.auth;
