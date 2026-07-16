package com.cryptopal.trading;

import com.cryptopal.auth.Wallet;
import com.cryptopal.auth.WalletRepository;
import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.cryptopal.market.MarketDataService;
import com.cryptopal.market.PriceQuote;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying and selling. The one place in this application where money moves.
 *
 * <p>Nothing here is real. Prices come from Binance, but no order reaches an exchange and
 * every balance is virtual. The rules are still written as though it mattered, because a
 * simulator that quietly loses a user's money is just as wrong as a real one.
 *
 * <p>Two properties this class exists to guarantee:
 *
 * <ul>
 *   <li><b>All or nothing.</b> One order is one transaction. The balance change, the
 *       position change, and the log entry either all land or none do. A fiat deduction
 *       that committed while the position update failed would silently destroy money.
 *   <li><b>One order at a time per wallet.</b> Every order takes a write lock on the
 *       wallet row before reading a balance, so two concurrent orders cannot both read
 *       1000, both decide 100 is affordable, and both write 900.
 * </ul>
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final WalletRepository wallets;
    private final HoldingRepository holdings;
    private final TransactionRepository transactions;
    private final MarketDataService marketData;

    public TradingService(WalletRepository wallets,
                          HoldingRepository holdings,
                          TransactionRepository transactions,
                          MarketDataService marketData) {
        this.wallets = wallets;
        this.holdings = holdings;
        this.transactions = transactions;
        this.marketData = marketData;
    }

    /**
     * Runs one order to completion, or changes nothing at all.
     *
     * <p>Throwing from anywhere below rolls the whole thing back, which is why the
     * rejections are plain exceptions rather than early returns carrying a status.
     */
    @Transactional
    public OrderResponse executeOrder(UUID userId, OrderRequest request) {
        // The price is read before the wallet is locked, and the order matters. This is a
        // Redis round trip, and doing it while holding a row lock would make every other
        // order for this wallet wait through it for no reason.
        //
        // It also fails fast: an unsupported symbol or a missing price refuses here,
        // before anything has been locked or touched.
        PriceQuote quote = marketData.getLatestPrice(request.symbol());

        // SELECT ... FOR UPDATE. Any other order for this wallet now waits here until this
        // one commits. See WalletRepository.findByUserIdForUpdate for why @Transactional
        // alone is not enough.
        Wallet wallet = wallets.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "This account has no wallet"));

        return switch (request.side()) {
            case BUY -> buy(userId, wallet, quote, request.amount());
            case SELL -> sell(userId, wallet, quote, request.amount());
        };
    }

    /** BUY: the amount is fiat to spend, and the quantity of crypto follows from the price. */
    private OrderResponse buy(UUID userId, Wallet wallet, PriceQuote quote, BigDecimal requestedSpend) {
        BigDecimal spend = MoneyMath.normalizeFiat(requestedSpend);
        if (spend.signum() <= 0) {
            // Caught here rather than by @DecimalMin: 0.001 is greater than zero and passes
            // validation, but rounds to 0.00 as a fiat amount. Left alone it would reach
            // MoneyMath, fail its positive check, and surface as a 500 for what is really
            // a bad request.
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Amount is smaller than one cent, so there is nothing to spend");
        }

        if (wallet.getFiatBalance().compareTo(spend) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient funds to complete this trade: balance is "
                            + wallet.getFiatBalance() + ", order needs " + spend);
        }

        // Rounded DOWN to 8 decimals, so a rounding error can never hand out more coin than
        // was paid for. The dust left behind is under a satoshi, well below a cent at any
        // price these assets trade at, and it stays with the house rather than being
        // created out of nothing.
        BigDecimal quantity = MoneyMath.buyQuantity(spend, quote.price());
        if (quantity.signum() <= 0) {
            // Reachable with a real price: spending 0.01 on an asset worth more than
            // 1,000,000 rounds to zero coin. Taking the money and delivering nothing would
            // be the worst possible outcome, so this refuses instead.
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Amount is too small to buy any " + quote.symbol() + " at the current price");
        }

        wallet.setFiatBalance(wallet.getFiatBalance().subtract(spend));
        Holding holding = addToPosition(wallet.getId(), quote.symbol(), quantity);
        Transaction transaction = transactions.save(new Transaction(
                userId, quote.symbol(), OrderSide.BUY, quantity, quote.price(), spend));

        log.debug("BUY {} {} at {} for {}", quantity, quote.symbol(), quote.price(), spend);
        return OrderResponse.of(transaction, wallet.getFiatBalance(), holding.getQuantity());
    }

    /** SELL: the amount is crypto quantity, and the fiat returned follows from the price. */
    private OrderResponse sell(UUID userId, Wallet wallet, PriceQuote quote, BigDecimal requestedQuantity) {
        BigDecimal quantity = MoneyMath.normalizeQuantity(requestedQuantity);
        if (quantity.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Quantity is smaller than the smallest tradeable unit of " + quote.symbol());
        }

        Holding holding = holdings.findByWalletIdAndSymbol(wallet.getId(), quote.symbol())
                .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_HOLDINGS,
                        "You do not hold any " + quote.symbol()));

        if (holding.getQuantity().compareTo(quantity) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_HOLDINGS,
                    "Insufficient " + quote.symbol() + " to complete this trade: you hold "
                            + holding.getQuantity() + ", order needs " + quantity);
        }

        BigDecimal proceeds = MoneyMath.fiatValue(quantity, quote.price());
        if (proceeds.signum() <= 0) {
            // The mirror of the buy case: selling a quantity worth less than a cent would
            // take the coin and pay nothing. It would also be rejected by the
            // fiat_amount > 0 constraint, but as a 500 rather than an explanation.
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "That quantity of " + quote.symbol() + " is worth less than a cent at the current price");
        }

        holding.setQuantity(holding.getQuantity().subtract(quantity));
        holdings.save(holding);
        wallet.setFiatBalance(wallet.getFiatBalance().add(proceeds));
        Transaction transaction = transactions.save(new Transaction(
                userId, quote.symbol(), OrderSide.SELL, quantity, quote.price(), proceeds));

        log.debug("SELL {} {} at {} for {}", quantity, quote.symbol(), quote.price(), proceeds);
        return OrderResponse.of(transaction, wallet.getFiatBalance(), holding.getQuantity());
    }

    /**
     * Adds to a position, creating it on the first buy of an asset.
     *
     * <p>The find-then-insert would be a race in general, but is not one here: the caller
     * holds the wallet's write lock, so no other order for this wallet can be between
     * these two statements. The unique index on (wallet_id, symbol) is the backstop if
     * that ever stops being true.
     */
    private Holding addToPosition(UUID walletId, String symbol, BigDecimal quantity) {
        Holding holding = holdings.findByWalletIdAndSymbol(walletId, symbol)
                .orElseGet(() -> new Holding(walletId, symbol, BigDecimal.ZERO));
        holding.setQuantity(holding.getQuantity().add(quantity));
        return holdings.save(holding);
    }
}
