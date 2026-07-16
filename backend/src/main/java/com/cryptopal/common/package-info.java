/**
 * Cross-cutting pieces that every other module leans on: application configuration,
 * the session auth filter, the global exception handler, and the single error response
 * shape the API returns.
 *
 * <p>This package is the one that others are allowed to depend on. It deliberately
 * depends on none of them, which is what stops the modules tangling into each other.
 */
package com.cryptopal.common;
