package com.cryptopal.trading;

import com.cryptopal.auth.Wallet;
import com.cryptopal.auth.WalletRepository;
import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.cryptopal.market.MarketDataService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads back what a user owns. A view over trading state, which is why it lives here
 * rather than in a package of its own.
 *
 * <p>Read-only by design. Nothing in this class writes, so nothing in it needs the wallet
 * lock that {@link TradingService} takes.
 */
@Service
public class PortfolioService {

    /** How many trades ride along with the portfolio. Enough to show recent activity. */
    private static final Limit RECENT_TRANSACTIONS = Limit.of(10);

    /** The cap on the dedicated history endpoint. */
    private static final Limit TRANSACTION_HISTORY = Limit.of(50);

    private final WalletRepository wallets;
    private final HoldingRepository holdings;
    private final TransactionRepository transactions;
    private final MarketDataService marketData;

    public PortfolioService(WalletRepository wallets,
                            HoldingRepository holdings,
                            TransactionRepository transactions,
                            MarketDataService marketData) {
        this.wallets = wallets;
        this.holdings = holdings;
        this.transactions = transactions;
        this.marketData = marketData;
    }

    /** The wallet, its positions valued at the latest prices, and recent activity. */
    @Transactional(readOnly = true)
    public Portfolio getPortfolio(UUID userId) {
        Wallet wallet = wallets.findByUserId(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "This account has no wallet"));

        // One timestamp for the whole valuation, so every line was priced at the same
        // moment and the parts add up to the total.
        Instant valuedAt = Instant.now();

        List<Portfolio.HoldingView> views = holdings.findByWalletId(wallet.getId()).stream()
                // Positions sold down to zero stay in the table, since the row is where the
                // next buy of that asset accumulates. They are not worth showing.
                .filter(holding -> holding.getQuantity().signum() > 0)
                .map(this::value)
                .toList();

        BigDecimal holdingsValue = views.stream()
                .map(Portfolio.HoldingView::estimatedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Portfolio(
                wallet.getFiatBalance(),
                views,
                holdingsValue,
                wallet.getFiatBalance().add(holdingsValue),
                recentTransactions(userId, RECENT_TRANSACTIONS),
                valuedAt);
    }

    /** A user's trade history, newest first. */
    @Transactional(readOnly = true)
    public List<TransactionView> getTransactions(UUID userId) {
        return recentTransactions(userId, TRANSACTION_HISTORY);
    }

    private Portfolio.HoldingView value(Holding holding) {
        // If a price is missing this throws PRICE_UNAVAILABLE and the whole portfolio
        // fails with it, rather than quietly reporting a position as worth nothing.
        // Showing someone a total that is wrong is worse than showing them an error.
        var quote = marketData.getLatestPrice(holding.getSymbol());
        return new Portfolio.HoldingView(
                holding.getSymbol(),
                holding.getQuantity(),
                quote.price(),
                MoneyMath.fiatValue(holding.getQuantity(), quote.price()));
    }

    private List<TransactionView> recentTransactions(UUID userId, Limit limit) {
        return transactions.findByUserIdOrderByCreatedAtDesc(userId, limit).stream()
                .map(TransactionView::from)
                .toList();
    }
}
