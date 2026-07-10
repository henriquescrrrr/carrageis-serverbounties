package pt.henrique.serverbounties.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.hook.CombatLogHook;
import pt.henrique.serverbounties.hook.FactionsHook;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.manager.DamageTracker;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.manager.SuspiciousDeathTracker;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.notification.NotificationManager;

import java.util.*;

/**
 * Handles the full death decision tree for bounty distribution and hitman contracts.
 *
 * Decision Tree:
 * 1. Determine valid killer(s) — combat tag + damage history
 * 2. Validate each killer — remove same-faction and self-damage
 * 3. Process BOUNTY — proportional distribution or suspicious death
 * 4. Process HITMAN CONTRACTS — (stubs for Part B)
 */
public final class DeathListener implements Listener {

    private final ServerBounties plugin;
    private final ConfigManager configManager;
    private final BountyManager bountyManager;
    private final HitmanManager hitmanManager;
    private final DamageTracker damageTracker;
    private final SuspiciousDeathTracker suspiciousDeathTracker;
    private final NotificationManager notificationManager;
    private final CombatLogHook combatLogHook;
    private final FactionsHook factionsHook;

    public DeathListener(ServerBounties plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.bountyManager = plugin.getBountyManager();
        this.hitmanManager = plugin.getHitmanManager();
        this.damageTracker = plugin.getDamageTracker();
        this.suspiciousDeathTracker = plugin.getSuspiciousDeathTracker();
        this.notificationManager = plugin.getNotificationManager();
        this.combatLogHook = plugin.getCombatLogHook();
        this.factionsHook = plugin.getFactionsHook();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player target = event.getEntity();
        UUID targetUuid = target.getUniqueId();
        Location deathLocation = target.getLocation();

        boolean hasBounty = bountyManager.hasBounty(targetUuid);
        boolean hasContracts = hitmanManager.hasActiveContracts(targetUuid);
        boolean isActiveHitman = !hitmanManager.getActiveContractsForHitman(targetUuid).isEmpty();

        // Skip processing if no bounty, no contracts on them, and not an active hitman
        if (!hasBounty && !hasContracts && !isActiveHitman) {
            damageTracker.clearRecords(targetUuid);
            return;
        }

        plugin.debug("Processing death of " + target.getName()
                + " (bounty: " + hasBounty + ", contracts: " + hasContracts
                + ", isHitman: " + isActiveHitman + ")");

        // ── Step 1: Determine valid killers ──────────────────────────────

        boolean isCombatTagged = combatLogHook.isInCombat(targetUuid);
        UUID combatTagger = combatLogHook.getAttacker(targetUuid);
        Player directKiller = target.getKiller();
        boolean hasPlayerKiller = directKiller != null || isCombatTagged;

        // Select time window based on death type
        long windowMs;
        if (hasPlayerKiller) {
            // Valid kill: player killed them or they were combat-tagged
            windowMs = configManager.getValidKillWindowMs();
        } else {
            // Suicide/environment: no player killer and no combat tag
            windowMs = configManager.getSuicideWindowMs();
        }

        // Build damage distribution map from DamageTracker
        Map<UUID, Double> damageDistribution = damageTracker.getDamageDistribution(targetUuid, windowMs);

        // If combat-tagged but tagger isn't in damage map, add them with minimum share
        if (isCombatTagged && combatTagger != null && !damageDistribution.containsKey(combatTagger)) {
            // Give the combat tagger a share. If map is empty, they get 100%.
            // If map has entries, insert with a small weight then re-normalize.
            if (damageDistribution.isEmpty()) {
                damageDistribution = new HashMap<>();
                damageDistribution.put(combatTagger, 1.0);
            } else {
                damageDistribution = new HashMap<>(damageDistribution);
                // Add tagger with average of existing shares
                double avgShare = damageDistribution.values().stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0.1);
                damageDistribution.put(combatTagger, avgShare);
                // Re-normalize to sum to 1.0
                normalize(damageDistribution);
            }
        }

        plugin.debug("Damage distribution for " + target.getName() + ": " + damageDistribution);

        // ── Step 2: Validate each killer ─────────────────────────────────

        // Remove target themselves (self-damage doesn't count)
        damageDistribution.remove(targetUuid);

        // Remove same-faction members
        if (!configManager.isAllowSameFaction()) {
            damageDistribution.entrySet().removeIf(entry ->
                    factionsHook.areInSameFaction(targetUuid, entry.getKey()));
        }

        // Re-normalize after removals
        if (!damageDistribution.isEmpty()) {
            normalize(damageDistribution);
        }

        plugin.debug("Validated killers for " + target.getName() + ": " + damageDistribution);

        // Store a snapshot for hitman processing
        final Map<UUID, Double> validKillers = Map.copyOf(damageDistribution);

        // ── Step 3: Process BOUNTY ───────────────────────────────────────

        if (!validKillers.isEmpty()) {
            // Valid killers exist → distribute bounty proportionally
            long totalBounty = bountyManager.getTotalBounty(targetUuid);

            bountyManager.claimBounty(targetUuid, validKillers)
                    .thenAccept(payouts -> {
                        if (!payouts.isEmpty()) {
                            // Must run notifications on main thread
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    notificationManager.notifyBountyClaimed(
                                            targetUuid, payouts, totalBounty, deathLocation));
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Error claiming bounty for " + target.getName()
                                + ": " + ex.getMessage());
                        return null;
                    });
        } else {
            // No valid killers → suspicious death
            processSuspiciousDeath(targetUuid, deathLocation);
        }

        // ── Step 4: Process HITMAN CONTRACTS (target died) ─────────────────

        if (hasContracts) {
            hitmanManager.processTargetDeath(targetUuid, validKillers)
                    .thenAccept(results -> {
                        if (!results.isEmpty()) {
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    notificationManager.notifyHitmanContractResults(results, deathLocation));
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Error processing hitman contracts for "
                                + target.getName() + ": " + ex.getMessage());
                        return null;
                    });
        }

        // ── Step 5: Process HITMAN DEATH (hitman killed by target) ───────

        // If the dead player is a hitman, check if any of their contract targets killed them.
        // This handles the scenario: target defends themselves and kills the hitman.
        if (!validKillers.isEmpty()) {
            java.util.List<HitmanContract> activeAsHitman = hitmanManager.getActiveContractsForHitman(targetUuid);
            for (HitmanContract contract : activeAsHitman) {
                if (validKillers.containsKey(contract.getTargetUuid())) {
                    // The contract target killed the hitman!
                    plugin.debug("Hitman " + target.getName() + " was killed by their contract target "
                            + contract.getTargetUuid() + " — processing hitmanKilledByTarget");
                    hitmanManager.hitmanKilledByTarget(contract)
                            .thenAccept(success -> {
                                if (success) {
                                    long amount = contract.getCurrentAmount();
                                    int clientPct = configManager.getHitmanKilledClientPercent();
                                    long clientRefund = amount * clientPct / 100;
                                    long targetReward = amount - clientRefund;

                                    plugin.getServer().getScheduler().runTask(plugin, () ->
                                            notificationManager.notifyHitmanKilledByTarget(
                                                    contract, clientRefund, targetReward));
                                }
                            })
                            .exceptionally(ex -> {
                                plugin.getLogger().warning("Error processing hitman-killed-by-target for contract "
                                        + contract.getId() + ": " + ex.getMessage());
                                return null;
                            });
                }
            }
        }

        // ── Cleanup ──────────────────────────────────────────────────────

        // Clear damage records for this target (death processed)
        damageTracker.clearRecords(targetUuid);
    }

    // ── Suspicious Death Processing ──────────────────────────────────────

    private void processSuspiciousDeath(UUID targetUuid, Location deathLocation) {
        suspiciousDeathTracker.recordSuspiciousDeath(targetUuid);
        int count = suspiciousDeathTracker.getRecentCount(targetUuid);

        plugin.debug("Suspicious death for " + targetUuid + ": " + count + "/"
                + configManager.getSuspiciousDeathThreshold());

        // Notify admins about the suspicious death
        notificationManager.notifySuspiciousDeath(targetUuid, count);

        // Check if threshold reached → reveal coords
        if (suspiciousDeathTracker.shouldRevealCoords(targetUuid)) {
            long bountyAmount = bountyManager.getTotalBounty(targetUuid);
            notificationManager.revealDeathchestCoords(targetUuid, deathLocation, bountyAmount);
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    /**
     * Normalize a damage distribution map so all values sum to 1.0.
     */
    private void normalize(Map<UUID, Double> distribution) {
        double total = distribution.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return;
        for (Map.Entry<UUID, Double> entry : distribution.entrySet()) {
            entry.setValue(entry.getValue() / total);
        }
    }
}


