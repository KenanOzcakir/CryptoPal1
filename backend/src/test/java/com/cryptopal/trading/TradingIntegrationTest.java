package com.cryptopal.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.cryptopal.auth.AuthService;
import com.cryptopal.auth.RegisterRequest;
import com.cryptopal.auth.Wallet;
import com.cryptopal.auth.WalletRepository;
import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import com.cryptopal.market.MarketDataService;
import com.cryptopal.market.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Buying, selling, and the guarantees around them, against real PostgreSQL. Needs
 * {@code docker compose up -d}.
 *
 * <p>The price is stubbed at a round 64000 rather than read from the live market, so the
 * arithmetic can be asserted exactly: 100 dollars buys precisely 0.00156250 BTC. Chasing a
 * real price would mean asserting "roughly", which would let a rounding bug through.
 * MarketDataService's own behaviour is covered in its own test.
 *
 * <p>Stubbing the bean also stops its 15 second scheduler running during the suite, so
 * these tests never touch the network.
 */
@SpringBootTest
class TradingIntegrationTest {

    private static final String TEST_DOMAIN = "@tradetest.local";
    private static final BigDecimal BTC_PRICE = new BigDecimal("64000.00000000");

    @MockitoBean
    private MarketDataService marketData;

    @Autowired
    private TradingService trading;
    @Autowired
    private PortfolioService portfolioService;
    @Autowired
    private AuthService authService;
    @Autowired
    private com.cryptopal.auth.UserRepository users;
    @Autowired
    private WalletRepository wallets;
    @Autowired
    private HoldingRepository holdings;
    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void stubTheMarket() {
        // Mimics the real service closely enough to be honest: BTC has a price, anything
        // else is refused the same way, and the symbol is normalised first.
        when(marketData.getLatestPrice(anyString())).thenAnswer(call -> {
            String requested = call.getArgument(0, String.class).trim().toUpperCase(java.util.Locale.ROOT);
            if ("BTC".equals(requested)) {
                return new PriceQuote("BTC", BTC_PRICE, Instant.now());
            }
            throw new ApiException(ErrorCode.UNSUPPORTED_SYMBOL, "Not a supported asset: " + requested);
        });
    }

    @AfterEach
    void removeWhatTheTestCreated() {
        // Deleting the user is enough: wallets, holdings, and transactions all cascade
        // from it in the schema.
        users.findAll().stream()
                .filter(user -> user.getEmail().endsWith(TEST_DOMAIN))
                .forEach(users::delete);
    }

    // ---------- buying ----------

    @Test
    void buyingSpendsFiatAndCreditsTheExactQuantity() {
        UUID userId = userWithBalance("50000.00");

        OrderResponse response = trading.executeOrder(userId, buy("100.00"));

        // 100 / 64000 = 0.0015625 exactly, so nothing is lost to rounding here.
        assertThat(response.quantity()).isEqualByComparingTo("0.00156250");
        assertThat(response.fiatAmount()).isEqualByComparingTo("100.00");
        assertThat(response.executionPrice()).isEqualByComparingTo(BTC_PRICE);
        assertThat(response.fiatBalance()).isEqualByComparingTo("49900.00");
        assertThat(response.holdingQuantity()).isEqualByComparingTo("0.00156250");

        // And it is actually in the database, not just in the response.
        assertThat(balanceOf(userId)).isEqualByComparingTo("49900.00");
        assertThat(positionIn(userId, "BTC")).isEqualByComparingTo("0.00156250");
    }

    @Test
    void buyingRoundsTheQuantityDownSoValueIsNeverInvented() {
        UUID userId = userWithBalance("50000.00");

        // 1 / 64000 = 0.000015625, which needs 9 decimals. Rounded down at 8 it becomes
        // 0.00000001, and the buyer gets slightly less than the arithmetic says rather
        // than slightly more.
        OrderResponse response = trading.executeOrder(userId, buy("1.00"));

        assertThat(response.quantity()).isEqualByComparingTo("0.00001562");
        // The crucial inequality: what was received is worth no more than what was paid.
        BigDecimal received = response.quantity().multiply(BTC_PRICE);
        assertThat(received).isLessThanOrEqualTo(response.fiatAmount());
    }

    @Test
    void buyingWithoutEnoughFundsIsRefusedAndChangesNothing() {
        UUID userId = userWithBalance("50.00");

        assertThatThrownBy(() -> trading.executeOrder(userId, buy("100.00")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        assertThat(balanceOf(userId)).isEqualByComparingTo("50.00");
        assertThat(holdings.findByWalletId(walletOf(userId).getId())).isEmpty();
        assertThat(transactions.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(10))).isEmpty();
    }

    @Test
    void buyingLessThanACentIsRefusedRatherThanCrashing() {
        UUID userId = userWithBalance("50000.00");

        // 0.001 passes the "greater than zero" check but rounds to 0.00 as a fiat amount.
        // Left unhandled this reaches MoneyMath and comes back as a 500.
        assertThatThrownBy(() -> trading.executeOrder(userId, buy("0.001")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThat(balanceOf(userId)).isEqualByComparingTo("50000.00");
    }

    @Test
    void spendingTooLittleToBuyAnyCoinIsRefusedRatherThanTakingTheMoney() {
        UUID userId = userWithBalance("50000.00");
        // A price high enough that one cent buys less than a satoshi.
        //
        // doReturn().when() rather than when().thenReturn(): the latter would actually
        // call getLatestPrice on the mock to work out what to stub, which fires the
        // answer installed in setUp and throws before the new stub is ever registered.
        org.mockito.Mockito.doReturn(new PriceQuote("BTC", new BigDecimal("2000000.00000000"), Instant.now()))
                .when(marketData).getLatestPrice(anyString());

        // The worst possible outcome would be taking the cent and delivering zero coin.
        assertThatThrownBy(() -> trading.executeOrder(userId, buy("0.01")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThat(balanceOf(userId)).isEqualByComparingTo("50000.00");
    }

    // ---------- selling ----------

    @Test
    void sellingReturnsFiatAndReducesThePosition() {
        UUID userId = userWithBalance("50000.00");
        trading.executeOrder(userId, buy("100.00"));

        OrderResponse response = trading.executeOrder(userId, sell("0.00100000"));

        // 0.001 * 64000 = 64.00
        assertThat(response.fiatAmount()).isEqualByComparingTo("64.00");
        assertThat(response.fiatBalance()).isEqualByComparingTo("49964.00");
        assertThat(response.holdingQuantity()).isEqualByComparingTo("0.00056250");
        assertThat(positionIn(userId, "BTC")).isEqualByComparingTo("0.00056250");
    }

    @Test
    void sellingEverythingLeavesTheBalanceWhereItStarted() {
        UUID userId = userWithBalance("50000.00");
        OrderResponse bought = trading.executeOrder(userId, buy("100.00"));

        OrderResponse sold = trading.executeOrder(userId, sell(bought.quantity().toPlainString()));

        // Round trip at an unchanged price returns exactly what was spent. Any drift here
        // would mean the rounding is creating or destroying money.
        assertThat(sold.fiatBalance()).isEqualByComparingTo("50000.00");
        assertThat(positionIn(userId, "BTC")).isEqualByComparingTo("0");
    }

    @Test
    void sellingMoreThanIsHeldIsRefused() {
        UUID userId = userWithBalance("50000.00");
        trading.executeOrder(userId, buy("100.00"));

        assertThatThrownBy(() -> trading.executeOrder(userId, sell("1.00000000")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_HOLDINGS);

        assertThat(positionIn(userId, "BTC")).isEqualByComparingTo("0.00156250");
        assertThat(balanceOf(userId)).isEqualByComparingTo("49900.00");
    }

    @Test
    void sellingSomethingNeverBoughtIsRefused() {
        UUID userId = userWithBalance("50000.00");

        assertThatThrownBy(() -> trading.executeOrder(userId, sell("1.00000000")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_HOLDINGS);
    }

    @Test
    void anAssetWeDoNotTradeIsRefused() {
        UUID userId = userWithBalance("50000.00");

        assertThatThrownBy(() -> trading.executeOrder(userId,
                new OrderRequest("DOGE", OrderSide.BUY, new BigDecimal("100.00"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UNSUPPORTED_SYMBOL);
    }

    // ---------- the log and the portfolio ----------

    @Test
    void everySuccessfulTradeIsLoggedAndRejectedOnesAreNot() {
        UUID userId = userWithBalance("300.00");
        trading.executeOrder(userId, buy("100.00"));
        trading.executeOrder(userId, buy("100.00"));
        assertThatThrownBy(() -> trading.executeOrder(userId, buy("500.00")))
                .isInstanceOf(ApiException.class);

        List<Transaction> logged = transactions.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(10));

        // Two, not three. A rejected order changed nothing, so there is nothing to record.
        assertThat(logged).hasSize(2);
        assertThat(logged).allSatisfy(entry -> {
            assertThat(entry.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(entry.getSymbol()).isEqualTo("BTC");
            assertThat(entry.getFiatAmount()).isEqualByComparingTo("100.00");
        });
    }

    @Test
    void theSideIsStoredAsTextSoTheDatabaseCheckAccepts() {
        UUID userId = userWithBalance("50000.00");

        trading.executeOrder(userId, buy("100.00"));

        // If the enum were mapped as an ordinal the column would get 0 and the
        // CHECK (side in ('BUY','SELL')) would have rejected the insert outright. Reading
        // it back as the enum proves the round trip.
        assertThat(transactions.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(1)))
                .first()
                .extracting(Transaction::getSide)
                .isEqualTo(OrderSide.BUY);
    }

    @Test
    void thePortfolioAddsUp() {
        UUID userId = userWithBalance("50000.00");
        trading.executeOrder(userId, buy("100.00"));

        Portfolio portfolio = portfolioService.getPortfolio(userId);

        assertThat(portfolio.fiatBalance()).isEqualByComparingTo("49900.00");
        assertThat(portfolio.holdings()).hasSize(1);
        // get(0), not getFirst(): getFirst() arrived with Java 21's SequencedCollection
        // and this project compiles for 17 so the jar runs on the VM's LTS runtime.
        assertThat(portfolio.holdings().get(0).symbol()).isEqualTo("BTC");
        assertThat(portfolio.holdings().get(0).estimatedValue()).isEqualByComparingTo("100.00");
        assertThat(portfolio.holdingsValue()).isEqualByComparingTo("100.00");
        // The whole point of the total: cash plus positions, and it must reconcile.
        assertThat(portfolio.totalValue()).isEqualByComparingTo("50000.00");
        assertThat(portfolio.recentTransactions()).hasSize(1);
    }

    @Test
    void aPositionSoldToZeroDropsOutOfThePortfolio() {
        UUID userId = userWithBalance("50000.00");
        OrderResponse bought = trading.executeOrder(userId, buy("100.00"));
        trading.executeOrder(userId, sell(bought.quantity().toPlainString()));

        Portfolio portfolio = portfolioService.getPortfolio(userId);

        // The row still exists, since the next buy accumulates into it, but showing
        // someone "BTC: 0" is noise.
        assertThat(portfolio.holdings()).isEmpty();
        assertThat(portfolio.totalValue()).isEqualByComparingTo("50000.00");
        assertThat(holdings.findByWalletId(walletOf(userId).getId())).hasSize(1);
    }

    // ---------- the one that matters ----------

    @Test
    void concurrentBuysCannotOverdrawTheWallet() throws Exception {
        // Exactly ten 100 dollar orders fit in this wallet. Twenty are fired at once.
        UUID userId = userWithBalance("1000.00");
        int attempts = 20;

        var startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startTogether.await();
                try {
                    trading.executeOrder(userId, buy("100.00"));
                    return true;
                } catch (ApiException expected) {
                    // INSUFFICIENT_FUNDS is the correct outcome for the ten that lose.
                    // Anything else is not caught here on purpose, so it fails the test.
                    return false;
                }
            }));
        }
        startTogether.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        // Without SELECT ... FOR UPDATE this is where it falls apart: several orders read
        // the same balance, all decide they can afford it, and all write back. More than
        // ten succeed and money appears from nowhere, with no error raised anywhere.
        assertThat(succeeded).as("orders that should fit in a 1000 balance").isEqualTo(10);
        assertThat(balanceOf(userId)).as("spent to the cent, never overdrawn").isEqualByComparingTo("0.00");
        assertThat(transactions.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(100)))
                .as("one log entry per successful order").hasSize(10);
        assertThat(positionIn(userId, "BTC"))
                .as("ten lots of 0.0015625").isEqualByComparingTo("0.01562500");
    }

    // ---------- helpers ----------

    private OrderRequest buy(String fiatToSpend) {
        return new OrderRequest("BTC", OrderSide.BUY, new BigDecimal(fiatToSpend));
    }

    private OrderRequest sell(String quantity) {
        return new OrderRequest("BTC", OrderSide.SELL, new BigDecimal(quantity));
    }

    private UUID userWithBalance(String balance) {
        var registered = authService.register(new RegisterRequest(
                "u" + UUID.randomUUID().toString().substring(0, 8) + TEST_DOMAIN, "StrongPassword123"));
        UUID userId = registered.user().id();
        // Overwrite the randomized starting balance, so the arithmetic below is knowable.
        Wallet wallet = wallets.findByUserId(userId).orElseThrow();
        wallet.setFiatBalance(new BigDecimal(balance));
        wallets.save(wallet);
        return userId;
    }

    private Wallet walletOf(UUID userId) {
        return wallets.findByUserId(userId).orElseThrow();
    }

    private BigDecimal balanceOf(UUID userId) {
        return walletOf(userId).getFiatBalance();
    }

    private BigDecimal positionIn(UUID userId, String symbol) {
        return holdings.findByWalletIdAndSymbol(walletOf(userId).getId(), symbol)
                .map(Holding::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
