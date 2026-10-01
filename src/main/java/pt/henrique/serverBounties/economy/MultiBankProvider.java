package pt.henrique.serverbounties.economy;

import org.bukkit.Bukkit;
import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Economy provider for MultiBank.
 * Uses reflection to avoid hard dependency on MultiBank classes.
 * Hooks into pt.henrique.multibank.api.MultiBankApi, published by MultiBank in the
 * Bukkit ServicesManager.
 * <p>
 * API flow: {@code getPrimaryAccount(UUID)} → {@code Account.getAccountId()} → the
 * balance and transfer calls, which are keyed by account ID and denominated in cents.
 * Both {@code withdraw} and {@code deposit} answer with a {@code TransactionResult}
 * whose {@code isSuccess()} carries the outcome.
 */
public final class MultiBankProvider implements EconomyProvider {

    private final ServerBounties plugin;

    private boolean available;
    private Object apiFacade; // MultiBankApi instance
    private Method getPrimaryAccountMethod;
    private Method getAccountIdMethod;
    private Method getBalanceCentsMethod;
    private Method withdrawMethod;
    private Method depositMethod;
    private Method isSuccessMethod;

    public MultiBankProvider(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            if (Bukkit.getPluginManager().getPlugin("MultiBank") == null) {
                plugin.debug("MultiBank not installed.");
                return;
            }

            Class<?> apiClass = Class.forName("pt.henrique.multibank.api.MultiBankApi");
            var registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null || registration.getProvider() == null) {
                plugin.debug("MultiBank API not registered in ServicesManager.");
                return;
            }
            this.apiFacade = registration.getProvider();

            // MultiBankApi.getPrimaryAccount(UUID) → CompletableFuture<Account>
            this.getPrimaryAccountMethod = apiClass.getMethod("getPrimaryAccount", UUID.class);

            // Account.getAccountId() → String
            Class<?> accountClass = Class.forName("pt.henrique.multibank.model.Account");
            this.getAccountIdMethod = accountClass.getMethod("getAccountId");

            // MultiBankApi.getBalanceCents(String accountId) → CompletableFuture<Long>
            this.getBalanceCentsMethod = apiClass.getMethod("getBalanceCents", String.class);

            // MultiBankApi.withdraw/deposit(String accountId, long cents, String reason, UUID actor)
            //   → CompletableFuture<TransactionResult>
            this.withdrawMethod = apiClass.getMethod("withdraw", String.class, long.class, String.class, UUID.class);
            this.depositMethod = apiClass.getMethod("deposit", String.class, long.class, String.class, UUID.class);

            Class<?> resultClass = Class.forName("pt.henrique.multibank.economy.TransactionResult");
            this.isSuccessMethod = resultClass.getMethod("isSuccess");

            this.available = true;
            plugin.debug("MultiBankProvider initialized successfully.");
        } catch (ClassNotFoundException e) {
            plugin.debug("MultiBank API not found on classpath.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize MultiBankProvider: " + e.getMessage());
            if (plugin.getConfigManager().isDebug()) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String getName() {
        return "MultiBank";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public CompletableFuture<Long> getBalanceCents(UUID playerUuid) {
        if (!available) return CompletableFuture.completedFuture(0L);

        return resolveAccountId(playerUuid).thenCompose(accountId -> {
            if (accountId == null) return CompletableFuture.completedFuture(0L);
            try {
                @SuppressWarnings("unchecked")
                CompletableFuture<Long> balanceFuture =
                        (CompletableFuture<Long>) getBalanceCentsMethod.invoke(apiFacade, accountId);
                return balanceFuture.thenApply(cents -> cents == null ? 0L : cents);
            } catch (Exception e) {
                plugin.getLogger().warning("MultiBankProvider: Error getting balance: " + e.getMessage());
                return CompletableFuture.completedFuture(0L);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("MultiBankProvider: Error getting balance: " + ex.getMessage());
            return 0L;
        });
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);
        return transact(withdrawMethod, "withdrawing", playerUuid, amountCents, reason);
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);
        return transact(depositMethod, "depositing", playerUuid, amountCents, reason);
    }

    private CompletableFuture<Boolean> transact(Method operation, String description,
                                                UUID playerUuid, long amountCents, String reason) {
        return resolveAccountId(playerUuid).thenCompose(accountId -> {
            if (accountId == null) return CompletableFuture.completedFuture(false);
            try {
                CompletableFuture<?> resultFuture = (CompletableFuture<?>)
                        operation.invoke(apiFacade, accountId, amountCents, reason, playerUuid);
                return resultFuture.thenApply(result -> {
                    try {
                        return result != null && (boolean) isSuccessMethod.invoke(result);
                    } catch (Exception e) {
                        plugin.getLogger().warning("MultiBankProvider: Error " + description + ": " + e.getMessage());
                        return false;
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("MultiBankProvider: Error " + description + ": " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("MultiBankProvider: Error " + description + ": " + ex.getMessage());
            return false;
        });
    }

    /**
     * Resolves the player's primary account ID, or null when they have no account.
     */
    private CompletableFuture<String> resolveAccountId(UUID playerUuid) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> accountFuture =
                    (CompletableFuture<Object>) getPrimaryAccountMethod.invoke(apiFacade, playerUuid);
            return accountFuture.thenApply(account -> {
                if (account == null) return null;
                try {
                    return (String) getAccountIdMethod.invoke(account);
                } catch (Exception e) {
                    return null;
                }
            }).exceptionally(ex -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public String formatCurrency(long amountCents) {
        // MultiBank uses cents — format as X.XX€
        long whole = amountCents / 100;
        long frac = Math.abs(amountCents % 100);
        if (frac == 0) {
            return String.format("%,d€", whole);
        }
        return String.format("%,d.%02d€", whole, frac);
    }
}

