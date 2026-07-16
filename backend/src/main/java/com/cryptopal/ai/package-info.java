/**
 * The Gemini-backed insight endpoint.
 *
 * <p>Gemini is called only from here, only from the backend, with the key read from the
 * environment, so it never reaches the browser. The service gathers the user's wallet,
 * holdings, recent trades, and recent prices, and hands Gemini a structured prompt
 * built from that context, so answers are grounded in real account data instead of
 * invented.
 *
 * <p>Every failure mode (timeout, rate limit, bad key, empty reply) is expected to
 * surface as a clean AI_UNAVAILABLE error rather than a crash.
 */
package com.cryptopal.ai;
