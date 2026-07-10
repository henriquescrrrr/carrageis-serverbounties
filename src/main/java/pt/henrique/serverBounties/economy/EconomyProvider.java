package pt.henrique.serverbounties.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Common interface for all economy providers.
 * All methods return CompletableFuture — never block the main thread.
 */
public interface EconomyProvider {

    /**
     * Get the name of this economy provider.
     */
    String getName();

    /**
     * Check if this provider is available and functional.
     */
    boolean isAvailable();

    /**
     * Get the balance of a player in cents.
     */
    CompletableFuture<Long> getBalanceCents(UUID playerUuid);

    /**
     * Withdraw an amount (in cents) from a player's account.
     *
     * @param playerUuid the player
     * @param amountCents the amount in cents
     * @param reason a human-readable reason for logging
     * @return true if the withdrawal succeeded
     */
    CompletableFuture<Boolean> withdraw(UUID playerUuid, long amountCents, String reason);

    /**
     * Deposit an amount (in cents) to a player's account.
     *
     * @param playerUuid the player
     * @param amountCents the amount in cents
     * @param reason a human-readable reason for logging
     * @return true if the deposit succeeded
     */
    CompletableFuture<Boolean> deposit(UUID playerUuid, long amountCents, String reason);

    /**
     * Format an amount in cents to a human-readable currency string.
     */
    String formatCurrency(long amountCents);
}

