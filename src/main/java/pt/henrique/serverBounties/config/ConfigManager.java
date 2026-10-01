package pt.henrique.serverbounties.config;

import org.bukkit.configuration.file.FileConfiguration;
import pt.henrique.serverbounties.ServerBounties;

import java.util.List;

public final class ConfigManager {

    private final ServerBounties plugin;

    // General
    private String globalLanguage;
    private boolean debug;

    // Economy
    private String economyMode;
    private List<String> economyPriority;

    // Bounty
    private long minAmount;
    private long maxAmountPerContribution;
    private long maxTotalPerTarget;
    private double taxRate;
    private boolean allowOfflineTargets;
    private long broadcastThreshold;
    private boolean allowSameFaction;
    private boolean cancelBlockedInCombat;

    // Damage tracking
    private int validKillWindowSeconds;
    private int suicideWindowSeconds;
    private int hitNotificationCooldownSeconds;

    // Anti-exploit
    private int suspiciousDeathThreshold;
    private int suspiciousDeathWindowMinutes;
    private boolean revealDeathchestCoords;

    // Notifications
    private boolean bountyPlacedBroadcast;
    private boolean bountyClaimedBroadcast;
    private boolean targetBossbar;
    private String targetBossbarColor;
    private boolean hitmanTargetBossbar;
    private String hitmanTargetBossbarColor;

    // Sounds
    private String soundBountyPlaced;
    private String soundBountyClaimed;
    private String soundHitBountyTarget;
    private String soundCoordsRevealed;

    // Hitman
    private int staleNegotiationHours;
    private boolean anonymousBroadcast;
    private int hitmanKilledClientPercent;
    private long defaultHitmanMinPrice;
    private int defaultHitmanMinHours;
    private int defaultHitmanMaxHours;
    private int defaultHitmanMaxConcurrent;
    private String soundContractProposed;
    private String soundContractAccepted;
    private String soundContractCompleted;
    private String soundContractCancelled;
    private String soundCounterOffer;

    public ConfigManager(ServerBounties plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration cfg = plugin.getConfig();

        // General
        this.globalLanguage = cfg.getString("language", "en_US");
        this.debug = cfg.getBoolean("debug", false);

        // Economy
        this.economyMode = cfg.getString("economy.mode", "auto");
        this.economyPriority = cfg.getStringList("economy.priority");
        if (economyPriority.isEmpty()) {
            economyPriority = List.of("MultiBank", "Vault");
        }

        // Bounty
        this.minAmount = cfg.getLong("bounty.min-amount", 500);
        this.maxAmountPerContribution = cfg.getLong("bounty.max-amount-per-contribution", 1_000_000);
        this.maxTotalPerTarget = cfg.getLong("bounty.max-total-per-target", 10_000_000);
        // A negative tax rate would make totalCost < amountCents: the contributor is charged
        // less than the bounty that gets escrowed, so every placement mints the difference.
        // NaN would silently drop the tax to zero via Math.round.
        double rawTaxRate = cfg.getDouble("bounty.tax-rate", 0.10);
        this.taxRate = Double.isFinite(rawTaxRate) ? Math.max(0.0, rawTaxRate) : 0.10;
        this.allowOfflineTargets = cfg.getBoolean("bounty.allow-offline-targets", false);
        this.broadcastThreshold = cfg.getLong("bounty.broadcast-threshold", 5000);
        this.allowSameFaction = cfg.getBoolean("bounty.allow-same-faction", false);
        this.cancelBlockedInCombat = cfg.getBoolean("bounty.cancel-blocked-in-combat", true);

        // Damage tracking
        this.validKillWindowSeconds = cfg.getInt("damage-tracking.valid-kill-window-seconds", 300);
        this.suicideWindowSeconds = cfg.getInt("damage-tracking.suicide-window-seconds", 150);
        this.hitNotificationCooldownSeconds = cfg.getInt("damage-tracking.hit-notification-cooldown-seconds", 10);

        // Anti-exploit
        this.suspiciousDeathThreshold = cfg.getInt("anti-exploit.suspicious-death-threshold", 7);
        this.suspiciousDeathWindowMinutes = cfg.getInt("anti-exploit.suspicious-death-window-minutes", 60);
        this.revealDeathchestCoords = cfg.getBoolean("anti-exploit.reveal-deathchest-coords", true);

        // Notifications
        this.bountyPlacedBroadcast = cfg.getBoolean("notifications.bounty-placed-broadcast", true);
        this.bountyClaimedBroadcast = cfg.getBoolean("notifications.bounty-claimed-broadcast", true);
        this.targetBossbar = cfg.getBoolean("notifications.target-bossbar", true);
        this.targetBossbarColor = cfg.getString("notifications.target-bossbar-color", "RED");
        this.hitmanTargetBossbar = cfg.getBoolean("notifications.hitman-target-bossbar", true);
        this.hitmanTargetBossbarColor = cfg.getString("notifications.hitman-target-bossbar-color", "PURPLE");

        // Sounds
        this.soundBountyPlaced = cfg.getString("sounds.bounty-placed", "minecraft:entity.experience_orb.pickup");
        this.soundBountyClaimed = cfg.getString("sounds.bounty-claimed", "minecraft:ui.toast.challenge_complete");
        this.soundHitBountyTarget = cfg.getString("sounds.hit-bounty-target", "minecraft:block.note_block.pling");
        this.soundCoordsRevealed = cfg.getString("sounds.coords-revealed", "minecraft:entity.elder_guardian.curse");

        // Hitman
        this.staleNegotiationHours = cfg.getInt("hitman.stale-negotiation-hours", 24);
        this.anonymousBroadcast = cfg.getBoolean("hitman.anonymous-broadcast", true);
        // Clamped like the rescission percentage: outside [0,100] one of the two shares of
        // the escrowed contract value goes negative and is handed to a deposit call.
        this.hitmanKilledClientPercent =
                Math.max(0, Math.min(100, cfg.getInt("hitman.hitman-killed-client-percent", 85)));
        this.defaultHitmanMinPrice = cfg.getLong("hitman.defaults.min-price", 100000);
        this.defaultHitmanMinHours = cfg.getInt("hitman.defaults.min-hours", 1);
        this.defaultHitmanMaxHours = cfg.getInt("hitman.defaults.max-hours", 168);
        this.defaultHitmanMaxConcurrent = cfg.getInt("hitman.defaults.max-concurrent", 3);
        this.soundContractProposed = cfg.getString("sounds.contract-proposed", "minecraft:entity.evoker.prepare_attack");
        this.soundContractAccepted = cfg.getString("sounds.contract-accepted", "minecraft:ui.toast.challenge_complete");
        this.soundContractCompleted = cfg.getString("sounds.contract-completed", "minecraft:ui.toast.challenge_complete");
        this.soundContractCancelled = cfg.getString("sounds.contract-cancelled", "minecraft:block.anvil.land");
        this.soundCounterOffer = cfg.getString("sounds.counter-offer", "minecraft:block.note_block.pling");

        plugin.debug("Config loaded. Language: " + globalLanguage + ", Economy mode: " + economyMode);
    }

    // --- Getters ---

    public String getGlobalLanguage() { return globalLanguage; }
    public boolean isDebug() { return debug; }

    public String getEconomyMode() { return economyMode; }
    public List<String> getEconomyPriority() { return economyPriority; }

    public long getMinAmount() { return minAmount; }
    public long getMaxAmountPerContribution() { return maxAmountPerContribution; }
    public long getMaxTotalPerTarget() { return maxTotalPerTarget; }
    public double getTaxRate() { return taxRate; }
    public boolean isAllowOfflineTargets() { return allowOfflineTargets; }
    public long getBroadcastThreshold() { return broadcastThreshold; }
    public boolean isAllowSameFaction() { return allowSameFaction; }
    public boolean isCancelBlockedInCombat() { return cancelBlockedInCombat; }

    public int getValidKillWindowSeconds() { return validKillWindowSeconds; }
    public int getSuicideWindowSeconds() { return suicideWindowSeconds; }
    public int getHitNotificationCooldownSeconds() { return hitNotificationCooldownSeconds; }

    public int getSuspiciousDeathThreshold() { return suspiciousDeathThreshold; }
    public int getSuspiciousDeathWindowMinutes() { return suspiciousDeathWindowMinutes; }
    public boolean isRevealDeathchestCoords() { return revealDeathchestCoords; }

    public boolean isBountyPlacedBroadcast() { return bountyPlacedBroadcast; }
    public boolean isBountyClaimedBroadcast() { return bountyClaimedBroadcast; }
    public boolean isTargetBossbar() { return targetBossbar; }
    public String getTargetBossbarColor() { return targetBossbarColor; }
    public boolean isHitmanTargetBossbar() { return hitmanTargetBossbar; }
    public String getHitmanTargetBossbarColor() { return hitmanTargetBossbarColor; }

    public String getSoundBountyPlaced() { return soundBountyPlaced; }
    public String getSoundBountyClaimed() { return soundBountyClaimed; }
    public String getSoundHitBountyTarget() { return soundHitBountyTarget; }
    public String getSoundCoordsRevealed() { return soundCoordsRevealed; }

    public long getValidKillWindowMs() { return validKillWindowSeconds * 1000L; }
    public long getSuicideWindowMs() { return suicideWindowSeconds * 1000L; }
    public long getHitNotificationCooldownMs() { return hitNotificationCooldownSeconds * 1000L; }
    public long getSuspiciousDeathWindowMs() { return suspiciousDeathWindowMinutes * 60_000L; }

    public int getStaleNegotiationHours() { return staleNegotiationHours; }
    public long getStaleNegotiationMs() { return staleNegotiationHours * 3_600_000L; }
    public boolean isAnonymousBroadcast() { return anonymousBroadcast; }
    public int getHitmanKilledClientPercent() { return hitmanKilledClientPercent; }
    public int getHitmanKilledTargetPercent() { return 100 - hitmanKilledClientPercent; }
    public long getDefaultHitmanMinPrice() { return defaultHitmanMinPrice; }
    public int getDefaultHitmanMinHours() { return defaultHitmanMinHours; }
    public int getDefaultHitmanMaxHours() { return defaultHitmanMaxHours; }
    public int getDefaultHitmanMaxConcurrent() { return defaultHitmanMaxConcurrent; }
    public String getSoundContractProposed() { return soundContractProposed; }
    public String getSoundContractAccepted() { return soundContractAccepted; }
    public String getSoundContractCompleted() { return soundContractCompleted; }
    public String getSoundContractCancelled() { return soundContractCancelled; }
    public String getSoundCounterOffer() { return soundCounterOffer; }
}
