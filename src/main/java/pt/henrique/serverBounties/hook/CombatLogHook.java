package pt.henrique.serverbounties.hook;

import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Hook into CombatLOG API via reflection.
 * API class: pt.henrique.combatlog.api.CombatLogApi
 */
public final class CombatLogHook {

    private final ServerBounties plugin;

    private boolean available;
    private Object apiInstance;
    private Method isInCombatMethod;
    private Method getAttackerMethod;

    public CombatLogHook(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            Class<?> apiClass = Class.forName("pt.henrique.combatlog.api.CombatLogApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            this.apiInstance = getInstanceMethod.invoke(null);
            this.isInCombatMethod = apiClass.getMethod("isInCombat", UUID.class);
            this.getAttackerMethod = apiClass.getMethod("getAttacker", UUID.class);
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
            Object result = getAttackerMethod.invoke(apiInstance, playerUuid);
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

