package pt.henrique.serverbounties;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import pt.henrique.serverbounties.command.BountyCommand;
import pt.henrique.serverbounties.command.HitmanCommand;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.gui.GuiListener;
import pt.henrique.serverbounties.hook.CombatLogHook;
import pt.henrique.serverbounties.hook.FactionsHook;
import pt.henrique.serverbounties.hook.PlayerSettingsHook;
import pt.henrique.serverbounties.listener.DamageListener;
import pt.henrique.serverbounties.listener.DeathListener;
import pt.henrique.serverbounties.listener.PlayerConnectionListener;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.manager.DamageTracker;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.manager.SuspiciousDeathTracker;
import pt.henrique.serverbounties.notification.NotificationManager;
import pt.henrique.serverbounties.storage.DatabaseManager;

import java.util.logging.Level;

public final class ServerBounties extends JavaPlugin {

    private static ServerBounties instance;

    private ConfigManager configManager;
    private GuiConfig guiConfig;
    private LangManager langManager;
    private EconomyManager economyManager;
    private PlayerSettingsHook playerSettingsHook;
    private CombatLogHook combatLogHook;
    private FactionsHook factionsHook;

    private DatabaseManager databaseManager;
    private BountyManager bountyManager;
    private HitmanManager hitmanManager;
    private DamageTracker damageTracker;
    private SuspiciousDeathTracker suspiciousDeathTracker;
    private NotificationManager notificationManager;

    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        instance = this;

        // Register permissions at runtime. Paper plugins (paper-plugin.yml) do NOT read a
        // permissions: block, so without this every gated command would fall back to the
        // Bukkit default (op-only) and regular players could not use /bounty or /hitman.
        registerPermissions();

        // Config & Lang
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.guiConfig = new GuiConfig(this);

        this.playerSettingsHook = new PlayerSettingsHook(this);
        this.langManager = new LangManager(this, configManager, playerSettingsHook);
        this.langManager.load();

        // Economy
        this.economyManager = new EconomyManager(this, configManager);
        this.economyManager.setup();

        // Hooks
        this.combatLogHook = new CombatLogHook(this);
        this.factionsHook = new FactionsHook(this);

        // Database
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.init();

        // Managers
        this.bountyManager = new BountyManager(this, configManager, economyManager, databaseManager);
        this.hitmanManager = new HitmanManager(this, configManager, economyManager, databaseManager);
        this.damageTracker = new DamageTracker(this);
        this.suspiciousDeathTracker = new SuspiciousDeathTracker(this, configManager);

        // Notifications (depends on bountyManager, economyManager, langManager)
        this.notificationManager = new NotificationManager(this, configManager, langManager, economyManager, bountyManager);

        // Load bounty cache from DB (async — does not block startup)
        bountyManager.loadCache().thenRun(() ->
                getLogger().info("Bounty cache loaded successfully.")
        ).exceptionally(ex -> {
            getLogger().severe("Failed to load bounty cache: " + ex.getMessage());
            return null;
        });

        // Load hitman contract cache from DB (async)
        hitmanManager.loadCache().thenRun(() -> {
                getLogger().info("Hitman contract cache loaded successfully.");
                // Start expiry timer after cache is loaded
                getServer().getScheduler().runTask(this, () -> hitmanManager.startExpiryTimer());
        }).exceptionally(ex -> {
            getLogger().severe("Failed to load hitman contract cache: " + ex.getMessage());
            return null;
        });

        // Register listeners
        Bukkit.getPluginManager().registerEvents(new DamageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DeathListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);

        // Register commands (Brigadier)
        new BountyCommand(this).register();
        new HitmanCommand(this).register();

        // Start cleanup tasks (every 60 seconds = 1200 ticks)
        this.cleanupTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            damageTracker.cleanup();
            suspiciousDeathTracker.cleanupAll();
            notificationManager.cleanupCooldowns();
        }, 1200L, 1200L).getTaskId();

        if (economyManager.isAvailable()) {
            getLogger().info("Economy provider: " + economyManager.getProviderName());
        } else {
            getLogger().warning("No economy provider found! Bounty features will be limited.");
        }

        getLogger().info("ServerBounties v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel cleanup task
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
        }

        // Stop hitman expiry timer
        if (hitmanManager != null) {
            hitmanManager.stopExpiryTimer();
        }

        // Shutdown database connection pool
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("ServerBounties disabled.");
        instance = null;
    }

    /**
     * Register the plugin's permission nodes with explicit defaults so that Brigadier
     * {@code .requires(...)} checks resolve correctly for non-op players. Idempotent:
     * skips nodes already present (e.g. if a permissions plugin defined them first).
     */
    private void registerPermissions() {
        org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
        addPermission(pm, "serverbounties.bounty", PermissionDefault.TRUE,
                "Place bounties and view the bounty board.");
        addPermission(pm, "serverbounties.hitman", PermissionDefault.TRUE,
                "Use the hitman contract system.");
        addPermission(pm, "serverbounties.admin", PermissionDefault.OP,
                "Admin commands (remove bounties, reload).");
    }

    private void addPermission(org.bukkit.plugin.PluginManager pm, String node,
                               PermissionDefault def, String description) {
        if (pm.getPermission(node) != null) return; // already registered — don't override
        try {
            pm.addPermission(new Permission(node, description, def));
        } catch (IllegalArgumentException ex) {
            // Raced with another registration — harmless.
            getLogger().fine("Permission already registered: " + node);
        }
    }

    public void reload() {
        configManager.load();
        langManager.load();
        economyManager.setup();
        // Reload bounty cache from DB
        bountyManager.loadCache().thenRun(() ->
                getLogger().info("Bounty cache reloaded.")
        );
        // Reload hitman contract cache from DB
        hitmanManager.loadCache().thenRun(() ->
                getLogger().info("Hitman contract cache reloaded.")
        );
    }

    public static ServerBounties getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public GuiConfig getGuiConfig() {
        return guiConfig;
    }

    public LangManager getLangManager() {
        return langManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public PlayerSettingsHook getPlayerSettingsHook() {
        return playerSettingsHook;
    }

    public CombatLogHook getCombatLogHook() {
        return combatLogHook;
    }

    public FactionsHook getFactionsHook() {
        return factionsHook;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public HitmanManager getHitmanManager() {
        return hitmanManager;
    }

    public DamageTracker getDamageTracker() {
        return damageTracker;
    }

    public SuspiciousDeathTracker getSuspiciousDeathTracker() {
        return suspiciousDeathTracker;
    }

    public NotificationManager getNotificationManager() {
        return notificationManager;
    }

    public void debug(String message) {
        if (configManager.isDebug()) {
            getLogger().log(Level.INFO, "[DEBUG] " + message);
        }
    }
}
