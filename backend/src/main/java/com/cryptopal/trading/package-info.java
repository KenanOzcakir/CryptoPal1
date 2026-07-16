/**
 * Buying, selling, and reading back the resulting portfolio.
 *
 * <p>This module holds the rules that must not be got wrong: an order runs inside one
 * transaction and takes a lock on the wallet row, so two concurrent trades cannot both
 * read the old balance and overwrite each other. Every amount is a BigDecimal.
 *
 * <p>{@code PortfolioService} lives here, rather than in a package of its own, because
 * a portfolio is a read view over trading state, not a separate concern.
 */
package com.cryptopal.trading;
