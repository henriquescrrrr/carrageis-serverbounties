package pt.henrique.serverbounties.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import pt.henrique.serverbounties.ServerBounties;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Economy provider backed by Vault.
 * Vault operations are not natively async, so we wrap them in CompletableFuture
 * running on the Bukkit async scheduler.
 */
public final class VaultProvider implements EconomyProvider {

    private final ServerBounties plugin;
    private Economy vaultEconomy;
    private boolean available;

    public VaultProvider(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.debug("Vault plugin not found.");
            return;
        }

        try {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.available = true;
                plugin.debug("VaultProvider initialized with: " + vaultEconomy.getName());
            } else {
                plugin.debug("Vault economy service not registered.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize VaultProvider: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "Vault" + (vaultEconomy != null ? " (" + vaultEconomy.getName() + ")" : "");
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public CompletableFuture<Long> getBalanceCents(UUID playerUuid) {
        if (!available) return CompletableFuture.completedFuture(0L);

        return CompletableFuture.supplyAsync(() -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            double balance = vaultEconomy.getBalance(player);
            // Vault uses doubles — convert to cents
            return Math.round(balance * 100.0);
        });
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);

        return CompletableFuture.supplyAsync(() -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            double amount = amountCents / 100.0;
            EconomyResponse response = vaultEconomy.withdrawPlayer(player, amount);
            if (!response.transactionSuccess()) {
                plugin.debug("Vault withdraw failed: " + response.errorMessage);
            }
            return response.transactionSuccess();
        });
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID playerUuid, long amountCents, String reason) {
        if (!available) return CompletableFuture.completedFuture(false);

        return CompletableFuture.supplyAsync(() -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            double amount = amountCents / 100.0;
            EconomyResponse response = vaultEconomy.depositPlayer(player, amount);
            if (!response.transactionSuccess()) {
                plugin.debug("Vault deposit failed: " + response.errorMessage);
            }
            return response.transactionSuccess();
        });
    }

    @Override
    public String formatCurrency(long amountCents) {
        if (available && vaultEconomy != null) {
            double amount = amountCents / 100.0;
            return vaultEconomy.format(amount);
        }
        long whole = amountCents / 100;
        long frac = Math.abs(amountCents % 100);
        if (frac == 0) {
            return String.format("$%,d", whole);
        }
        return String.format("$%,d.%02d", whole, frac);
    }
}

