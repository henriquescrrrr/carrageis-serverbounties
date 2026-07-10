package pt.henrique.serverbounties.economy;

import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Economy provider for MultiBank.
 * Uses reflection to avoid hard dependency on MultiBank classes.
 * Hooks into: pt.henrique.multibank.api.MultiBankApi
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

    public MultiBankProvider(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            Class<?> apiClass = Class.forName("pt.henrique.multibank.api.MultiBankApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            this.apiFacade = getInstanceMethod.invoke(null);

            // MultiBankApi.getPrimaryAccount(UUID) → returns CompletableFuture<Account>
            this.getPrimaryAccountMethod = apiClass.getMethod("getPrimaryAccount", UUID.class);

            // Account.getAccountId() → returns some ID (String or long)
            Class<?> accountClass = Class.forName("pt.henrique.multibank.api.Account");
            this.getAccountIdMethod = accountClass.getMethod("getAccountId");
            this.getBalanceCentsMethod = accountClass.getMethod("getBalanceCents");

            // MultiBankApi.withdraw(accountId, long cents, String reason, UUID actor) → CompletableFuture<Boolean>
            // Determine account ID type from return type of getAccountId
            Class<?> accountIdType = getAccountIdMethod.getReturnType();
            this.withdrawMethod = apiClass.getMethod("withdraw", accountIdType, long.class, String.class, UUID.class);
            this.depositMethod = apiClass.getMethod("deposit", accountIdType, long.class, String.class, UUID.class);

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
    @SuppressWarnings("unchecked")
    public CompletableFuture<Long> getBalanceCents(UUID playerUuid) {
        if (!available) return CompletableFuture.completedFuture(0L);

        try {
            CompletableFuture<Object> accountFuture =
                    (CompletableFuture<Object>) getPrimaryAccountMethod.invoke(apiFacade, playerUuid);

            return accountFuture.thenApply(account -> {
                try {
                    if (account == null) return 0L;
                    Object balance = getBalanceCentsMethod.invoke(account);
                    return ((Number) balance).longValue();
                } catch (Exception e) {
                    plugin.getLogger().warning("MultiBankProvider: Error getting balance: " + e.getMessage());
                    return 0L;
                }
            }).exceptionally(ex -> {
                plugin.getLogger().warning("MultiBankProvider: Error getting balance: " + ex.getMessage());
                return 0L;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("MultiBankProvider: Error invoking getBalanceCents: " + e.getMessage());
            return CompletableFuture.completedFuture(0L);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> withdraw(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);

        try {
            CompletableFuture<Object> accountFuture =
                    (CompletableFuture<Object>) getPrimaryAccountMethod.invoke(apiFacade, playerUuid);

            return accountFuture.thenCompose(account -> {
                try {
                    if (account == null) return CompletableFuture.completedFuture(false);
                    Object accountId = getAccountIdMethod.invoke(account);
                    CompletableFuture<Boolean> result =
                            (CompletableFuture<Boolean>) withdrawMethod.invoke(apiFacade, accountId, amountCents, reason, playerUuid);
                    return result;
                } catch (Exception e) {
                    plugin.getLogger().warning("MultiBankProvider: Error withdrawing: " + e.getMessage());
                    return CompletableFuture.completedFuture(false);
                }
            }).exceptionally(ex -> {
                plugin.getLogger().warning("MultiBankProvider: Error withdrawing: " + ex.getMessage());
                return false;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("MultiBankProvider: Error invoking withdraw: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> deposit(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);

        try {
            CompletableFuture<Object> accountFuture =
                    (CompletableFuture<Object>) getPrimaryAccountMethod.invoke(apiFacade, playerUuid);

            return accountFuture.thenCompose(account -> {
                try {
                    if (account == null) return CompletableFuture.completedFuture(false);
                    Object accountId = getAccountIdMethod.invoke(account);
                    CompletableFuture<Boolean> result =
                            (CompletableFuture<Boolean>) depositMethod.invoke(apiFacade, accountId, amountCents, reason, playerUuid);
                    return result;
                } catch (Exception e) {
                    plugin.getLogger().warning("MultiBankProvider: Error depositing: " + e.getMessage());
                    return CompletableFuture.completedFuture(false);
                }
            }).exceptionally(ex -> {
                plugin.getLogger().warning("MultiBankProvider: Error depositing: " + ex.getMessage());
                return false;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("MultiBankProvider: Error invoking deposit: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
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

