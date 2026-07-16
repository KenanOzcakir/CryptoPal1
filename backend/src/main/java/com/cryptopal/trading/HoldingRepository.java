package com.cryptopal.trading;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes crypto positions. */
public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    Optional<Holding> findByWalletIdAndSymbol(UUID walletId, String symbol);

    /**
     * Every position in a wallet, including any that have been sold down to zero.
     *
     * <p>No lock here, and none needed: every order for a wallet already serializes on
     * that wallet's row, so no two writers can be inside this wallet's holdings at once.
     */
    List<Holding> findByWalletId(UUID walletId);
}
