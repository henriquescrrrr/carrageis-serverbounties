package pt.henrique.serverbounties.economy;

import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages economy providers with priority-based fallback.
 * MultiBank is primary, Vault is fallback.
 */
public final class EconomyManager {

    private final ServerBounties plugin;
    private final ConfigManager configManager;

    private EconomyProvider activeProvider;

    public EconomyManager(ServerBounties plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Attempt to set up an economy provider based on config priority.
     */
    public void setup() {
        this.activeProvider = null;

        List<String> priority = configManager.getEconomyPriority();
        for (String providerName : priority) {
            EconomyProvider provider = createProvider(providerName);
            if (provider != null && provider.isAvailable()) {
                this.activeProvider = provider;
                plugin.getLogger().info("Economy provider selected: " + provider.getName());
                return;
            }
        }

        plugin.getLogger().warning("No economy provider could be initialized!");
    }

    private EconomyProvider createProvider(String name) {
        return switch (name.toLowerCase()) {
            case "multibank" -> new MultiBankProvider(plugin);
            case "vault" -> new VaultProvider(plugin);
            default -> {
                plugin.getLogger().warning("Unknown economy provider: " + name);
                yield null;
            }
        };
    }

    public boolean isAvailable() {
        return activeProvider != null && activeProvider.isAvailable();
    }

    public String getProviderName() {
        return activeProvider != null ? activeProvider.getName() : "None";
    }

    public CompletableFuture<Long> getBalanceCents(UUID playerUuid) {
        if (activeProvider == null) return CompletableFuture.completedFuture(0L);
        return activeProvider.getBalanceCents(playerUuid);
    }

    public CompletableFuture<Boolean> withdraw(UUID playerUuid, long amountCents, String reason) {
        if (activeProvider == null) return CompletableFuture.completedFuture(false);
        return activeProvider.withdraw(playerUuid, amountCents, reason);
    }

    public CompletableFuture<Boolean> deposit(UUID playerUuid, long amountCents, String reason) {
        if (activeProvider == null) return CompletableFuture.completedFuture(false);
        return activeProvider.deposit(playerUuid, amountCents, reason);
    }

    public String formatCurrency(long amountCents) {
        if (activeProvider == null) {
            long whole = amountCents / 100;
            long frac = Math.abs(amountCents % 100);
            if (frac == 0) return String.format("%,d", whole);
            return String.format("%,d.%02d", whole, frac);
        }
        return activeProvider.formatCurrency(amountCents);
    }

    public EconomyProvider getActiveProvider() {
        return activeProvider;
    }
}

