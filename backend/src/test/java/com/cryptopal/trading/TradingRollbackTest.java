package com.cryptopal.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.cryptopal.auth.AuthService;
import com.cryptopal.auth.RegisterRequest;
import com.cryptopal.auth.UserRepository;
import com.cryptopal.auth.Wallet;
import com.cryptopal.auth.WalletRepository;
import com.cryptopal.market.MarketDataService;
import com.cryptopal.market.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the all-or-nothing property that requirement 5.3.4 asks for: if any part of a
 * trade fails, the whole trade rolls back.
 *
 * <p>This needs a failure in the middle of an order, after the balance has already been
 * reduced, which nothing in the normal flow can produce. So the transaction log is
 * replaced with one that refuses to write. If the money moved anyway, the deduction would
 * have committed while the record of it did not, and the balance would be wrong with
 * nothing to explain why.
 *
 * <p>It lives in its own class because breaking a repository for one test would break
 * every other test sharing the context.
 */
@SpringBootTest
class TradingRollbackTest {

    private static final String TEST_DOMAIN = "@rollbacktest.local";

    @MockitoBean
    private MarketDataService marketData;

    @MockitoBean
    private TransactionRepository transactions;

    @Autowired
    private TradingService trading;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository users;
    @Autowired
    private WalletRepository wallets;
    @Autowired
    private HoldingRepository holdings;

    @AfterEach
    void removeWhatTheTestCreated() {
        users.findAll().stream()
                .filter(user -> user.getEmail().endsWith(TEST_DOMAIN))
                .forEach(users::delete);
    }

    @Test
    void whenTheTradeLogCannotBeWrittenTheMoneyDoesNotMove() {
        doReturn(new PriceQuote("BTC", new BigDecimal("64000.00000000"), Instant.now()))
                .when(marketData).getLatestPrice(anyString());
        // The log write is the last step of a buy, so by the time it runs the balance has
        // already been reduced and the position already increased in the persistence
        // context. This is the moment worth testing.
        doThrow(new RuntimeException("the database went away"))
                .when(transactions).save(any(Transaction.class));

        UUID userId = registerWithBalance("50000.00");

        assertThatThrownBy(() -> trading.executeOrder(userId,
                new OrderRequest("BTC", OrderSide.BUY, new BigDecimal("100.00"))))
                .isInstanceOf(RuntimeException.class);

        // Read back from the database, in a new transaction. Both of these would be wrong
        // if @Transactional were missing or the exception were swallowed: the balance
        // would show 49900 with no record of where the 100 went, and a position would
        // exist that was never paid for.
        assertThat(walletOf(userId).getFiatBalance())
                .as("balance is untouched, not 49900")
                .isEqualByComparingTo("50000.00");
        assertThat(holdings.findByWalletId(walletOf(userId).getId()))
                .as("no position was created")
                .isEmpty();
    }

    private UUID registerWithBalance(String balance) {
        var registered = authService.register(new RegisterRequest(
                "u" + UUID.randomUUID().toString().substring(0, 8) + TEST_DOMAIN, "StrongPassword123"));
        UUID userId = registered.user().id();
        Wallet wallet = wallets.findByUserId(userId).orElseThrow();
        wallet.setFiatBalance(new BigDecimal(balance));
        wallets.save(wallet);
        return userId;
    }

    private Wallet walletOf(UUID userId) {
        return wallets.findByUserId(userId).orElseThrow();
    }
}
