package pt.henrique.serverbounties.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pt.henrique.serverbounties.ServerBounties;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Hook into PlayerSettings API via reflection.
 * <p>
 * Access pattern: ServicesManager → PlayerSettingsApi provider → getLanguage(UUID).
 */
public final class PlayerSettingsHook {

    private final ServerBounties plugin;

    private boolean available;
    private Object providerInstance;
    private Method getLanguageMethod;

    public PlayerSettingsHook(ServerBounties plugin) {
        this.plugin = plugin;
        this.available = false;
        init();
    }

    private void init() {
        try {
            // 1. Get the PlayerSettings plugin from Bukkit
            Plugin psPlugin = Bukkit.getPluginManager().getPlugin("PlayerSettings");
            if (psPlugin == null || !psPlugin.isEnabled()) {
                plugin.debug("PlayerSettings plugin not found or not enabled.");
                return;
            }

            // 2. Get the provider registered by PlayerSettings in the ServicesManager
            Class<?> apiClass = Class.forName("pt.henrique.playersettings.api.PlayerSettingsApi");
            var registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null || registration.getProvider() == null) {
                plugin.debug("PlayerSettings API not registered in ServicesManager.");
                return;
            }
            this.providerInstance = registration.getProvider();

            // 3. Resolve getLanguage(UUID) on the API interface
            this.getLanguageMethod = apiClass.getMethod("getLanguage", UUID.class);

            this.available = true;
            plugin.getLogger().info("PlayerSettings hook initialized successfully.");
        } catch (ClassNotFoundException e) {
            plugin.debug("PlayerSettings API not found on classpath.");
        } catch (NoSuchMethodException e) {
            plugin.debug("PlayerSettings API method not found: " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize PlayerSettingsHook: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Get the language/locale for a player, or null if unavailable.
     */
    public String getLanguage(UUID playerUuid) {
        if (!available || providerInstance == null) return null;

        try {
            Object result = getLanguageMethod.invoke(providerInstance, playerUuid);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            plugin.debug("PlayerSettingsHook: Error getting language for " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }
}

