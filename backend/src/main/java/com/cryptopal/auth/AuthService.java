package com.cryptopal.auth;

import com.cryptopal.common.ApiException;
import com.cryptopal.common.ErrorCode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, and token checking. The rules that decide who gets in.
 */
@Service
public class AuthService {

    // The randomized starting balance range, in virtual USD. Wide enough that a user can
    // do something meaningful with any of the four tokens, including one BTC-sized trade.
    private static final int MIN_STARTING_BALANCE = 10_000;
    private static final int MAX_STARTING_BALANCE = 100_000;

    private static final int FIAT_SCALE = 2;

    private final UserRepository users;
    private final WalletRepository wallets;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Random random;

    // A hash of nothing in particular, used only to keep login's timing even. See login().
    private final String decoyHash;

    @Autowired
    public AuthService(UserRepository users,
                       WalletRepository wallets,
                       SessionService sessions,
                       PasswordEncoder passwordEncoder,
                       TransactionTemplate transactionTemplate) {
        this(users, wallets, sessions, passwordEncoder, transactionTemplate, new Random());
    }

    // Takes the Random so a test can seed it and get a predictable starting balance.
    AuthService(UserRepository users,
                WalletRepository wallets,
                SessionService sessions,
                PasswordEncoder passwordEncoder,
                TransactionTemplate transactionTemplate,
                Random random) {
        this.users = users;
        this.wallets = wallets;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.random = random;
        this.decoyHash = passwordEncoder.encode("no account will ever have this password");
    }

    /** Creates an account with a wallet, and logs it straight in. */
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String passwordHash = passwordEncoder.encode(request.password());

        User user = transactionTemplate.execute(status -> createUserWithWallet(email, passwordHash));

        // Deliberately outside the transaction above, and this ordering matters. Redis
        // takes no part in a PostgreSQL transaction, so a session written inside one
        // would survive a rollback and leave a valid token pointing at a user that was
        // never committed.
        String token = sessions.createSession(user);
        return AuthResponse.of(token, user);
    }

    /** Exchanges credentials for a session token. */
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalizeEmail(request.email()));

        if (found.isEmpty()) {
            // Hash the attempt against a throwaway value anyway. Without this, a missing
            // account answers in about a millisecond while a real one takes the tens of
            // milliseconds BCrypt costs, and that gap alone tells someone which email
            // addresses are registered.
            passwordEncoder.matches(request.password(), decoyHash);
            throw invalidCredentials();
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return AuthResponse.of(sessions.createSession(user), user);
    }

    /** Resolves a token to its owner, or refuses. */
    public AuthenticatedUser validateToken(String token) {
        return sessions.getSession(token)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED,
                        "Session is invalid or has expired"));
    }

    /** Ends a session. */
    public void logout(String token) {
        sessions.invalidate(token);
    }

    // Runs inside the transaction template above, so the user and the wallet either both
    // exist or neither does. An account with no wallet could not trade and could not be
    // repaired without a manual insert.
    private User createUserWithWallet(String email, String passwordHash) {
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "That email is already registered");
        }
        User user = new User(email, passwordHash);
        try {
            // saveAndFlush, not save: this sends the INSERT now, so the unique index is
            // tested here where it can be caught, rather than at commit where it would
            // surface as an unhandled error.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // The check above loses a race when two registrations for the same address
            // arrive together: both look, both find nothing, both insert. The unique
            // index is what actually decides it, so the loser lands here and gets the
            // same answer it would have got by arriving a moment later.
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL, "That email is already registered", e);
        }
        wallets.save(new Wallet(user.getId(), randomStartingBalance()));
        return user;
    }

    private BigDecimal randomStartingBalance() {
        int dollars = MIN_STARTING_BALANCE + random.nextInt(MAX_STARTING_BALANCE - MIN_STARTING_BALANCE + 1);
        return BigDecimal.valueOf(dollars).setScale(FIAT_SCALE);
    }

    private static String normalizeEmail(String email) {
        // Locale.ROOT is not decoration. My machine runs a Turkish locale, where
        // "I".toLowerCase() is the dotless "ı", so a default-locale lowercase would turn
        // KENAN@X.COM into kenanı@x.com style nonsense and the address would never match
        // what was stored. Pinning the locale means this behaves the same everywhere.
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        // One message for both "no such account" and "wrong password", on purpose. Two
        // different answers would let anyone check whether an address has an account.
        return new ApiException(ErrorCode.UNAUTHORIZED, "Invalid email or password");
    }
}
