package pt.henrique.serverbounties.hook;

import org.bukkit.Bukkit;
import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Hook into CombatLOG API via reflection.
 * API class: pt.henrique.combatlog.api.CombatLogApi, published by CombatLOG in the
 * Bukkit ServicesManager.
 */
public final class CombatLogHook {

    private final ServerBounties plugin;

    private boolean available;
    private Object apiInstance;
    private Method isInCombatMethod;
    private Method getLastOpponentMethod;

    public CombatLogHook(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            if (Bukkit.getPluginManager().getPlugin("CombatLOG") == null) {
                plugin.debug("CombatLOG not installed.");
                return;
            }

            Class<?> apiClass = Class.forName("pt.henrique.combatlog.api.CombatLogApi");
            var registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null || registration.getProvider() == null) {
                plugin.debug("CombatLOG API not registered in ServicesManager.");
                return;
            }
            this.apiInstance = registration.getProvider();
            this.isInCombatMethod = apiClass.getMethod("isInCombat", UUID.class);
            this.getLastOpponentMethod = apiClass.getMethod("getLastOpponent", UUID.class);
            this.available = true;
            plugin.debug("CombatLogHook initialized.");
        } catch (ClassNotFoundException e) {
            plugin.debug("CombatLOG API not found on classpath.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize CombatLogHook: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Check if a player is currently combat-tagged.
     */
    public boolean isInCombat(UUID playerUuid) {
        if (!available || apiInstance == null) return false;

        try {
            Object result = isInCombatMethod.invoke(apiInstance, playerUuid);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            plugin.debug("CombatLogHook: Error checking combat for " + playerUuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the UUID of the player who combat-tagged this player.
     * Returns null if not in combat or unavailable.
     */
    public UUID getAttacker(UUID playerUuid) {
        if (!available || apiInstance == null) return null;

        try {
            Object result = getLastOpponentMethod.invoke(apiInstance, playerUuid);
            if (result instanceof UUID uuid) {
                return uuid;
            }
            return null;
        } catch (Exception e) {
            plugin.debug("CombatLogHook: Error getting attacker for " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }
}

