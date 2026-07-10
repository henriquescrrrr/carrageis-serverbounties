package pt.henrique.serverbounties.hook;

import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Hook into ServerFactions API via reflection.
 * API class: pt.henrique.serverfactions.api.ServerFactionsApi
 */
public final class FactionsHook {

    private final ServerBounties plugin;

    private boolean available;
    private Object apiInstance;
    private Method areInSameFactionMethod;

    public FactionsHook(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            Class<?> apiClass = Class.forName("pt.henrique.serverfactions.api.ServerFactionsApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            this.apiInstance = getInstanceMethod.invoke(null);
            this.areInSameFactionMethod = apiClass.getMethod("areInSameFaction", UUID.class, UUID.class);
            this.available = true;
            plugin.debug("FactionsHook initialized.");
        } catch (ClassNotFoundException e) {
            plugin.debug("ServerFactions API not found on classpath.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize FactionsHook: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Check if two players are in the same faction.
     * Returns false if the hook is unavailable (safe default).
     */
    public boolean areInSameFaction(UUID player1, UUID player2) {
        if (!available || apiInstance == null) return false;

        try {
            Object result = areInSameFactionMethod.invoke(apiInstance, player1, player2);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            plugin.debug("FactionsHook: Error checking faction for " + player1 + " & " + player2 + ": " + e.getMessage());
            return false;
        }
    }
}

