package pt.henrique.serverbounties.notification;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.manager.HitmanManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all bounty and hitman notifications: BossBar, broadcasts, sounds, particles.
 */
public final class NotificationManager {

    private final ServerBounties plugin;
    private final ConfigManager configManager;
    private final LangManager langManager;
    private final EconomyManager economyManager;
    private final BountyManager bountyManager;

    /** target UUID → active bounty BossBar instance */
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    /** target UUID → active hitman contract BossBar instance */
    private final Map<UUID, BossBar> hitmanBossBars = new ConcurrentHashMap<>();

    public NotificationManager(ServerBounties plugin, ConfigManager configManager,
                               LangManager langManager, EconomyManager economyManager,
                               BountyManager bountyManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.langManager = langManager;
        this.economyManager = economyManager;
        this.bountyManager = bountyManager;
    }

    // ── Bounty BossBar ───────────────────────────────────────────────────

    /**
     * Show or update the bounty BossBar for a target player.
     * Called when a bounty is placed, on login if bounty exists, or when bounty changes.
     */
    public void updateBossBar(Player target) {
        if (!configManager.isTargetBossbar()) return;

        UUID targetUuid = target.getUniqueId();
        long totalBounty = bountyManager.getTotalBounty(targetUuid);

        if (totalBounty <= 0) {
            removeBossBar(target);
            return;
        }

        String formatted = economyManager.formatCurrency(totalBounty);
        Component barTitle = langManager.get(target, "bounty-target-bossbar",
                "amount", formatted);

        BossBar.Color color = parseBossBarColor(configManager.getTargetBossbarColor());

        BossBar existing = activeBossBars.get(targetUuid);
        if (existing != null) {
            existing.name(barTitle);
            existing.color(color);
        } else {
            BossBar bar = BossBar.bossBar(barTitle, 1.0f, color, BossBar.Overlay.PROGRESS);
            activeBossBars.put(targetUuid, bar);
            target.showBossBar(bar);
        }
    }

    /**
     * Remove the bounty BossBar from a target.
     */
    public void removeBossBar(Player target) {
        BossBar bar = activeBossBars.remove(target.getUniqueId());
        if (bar != null) {
            target.hideBossBar(bar);
        }
    }

    /**
     * Remove BossBar by UUID (e.g., when player is offline but bounty cleared).
     * The bar will be cleaned up when the player next logs in.
     */
    public void removeBossBarByUuid(UUID targetUuid) {
        BossBar bar = activeBossBars.remove(targetUuid);
        if (bar != null) {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target != null && target.isOnline()) {
                target.hideBossBar(bar);
            }
        }
    }

    // ── Hitman Contract BossBar ──────────────────────────────────────────

    /**
     * Show or update the hitman contract BossBar for a target player.
     * Uses a separate bar and color from the bounty BossBar.
     */
    public void updateHitmanBossBar(Player target) {
        if (!configManager.isHitmanTargetBossbar()) return;

        UUID targetUuid = target.getUniqueId();
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        boolean hasActiveContracts = hitmanMgr.hasActiveContracts(targetUuid);

        if (!hasActiveContracts) {
            removeHitmanBossBar(target);
            return;
        }

        Component barTitle = langManager.get(target, "hitman-target-bossbar");
        BossBar.Color color = parseBossBarColor(configManager.getHitmanTargetBossbarColor());

        BossBar existing = hitmanBossBars.get(targetUuid);
        if (existing != null) {
            existing.name(barTitle);
            existing.color(color);
        } else {
            BossBar bar = BossBar.bossBar(barTitle, 1.0f, color, BossBar.Overlay.PROGRESS);
            hitmanBossBars.put(targetUuid, bar);
            target.showBossBar(bar);
        }
    }

    /**
     * Remove the hitman contract BossBar from a target.
     */
    public void removeHitmanBossBar(Player target) {
        BossBar bar = hitmanBossBars.remove(target.getUniqueId());
        if (bar != null) {
            target.hideBossBar(bar);
        }
    }

    /**
     * Remove hitman BossBar by UUID.
     */
    public void removeHitmanBossBarByUuid(UUID targetUuid) {
        BossBar bar = hitmanBossBars.remove(targetUuid);
        if (bar != null) {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target != null && target.isOnline()) {
                target.hideBossBar(bar);
            }
        }
    }

    /**
     * Refresh the hitman BossBar after a contract ends — removes if no more active contracts.
     */
    private void refreshHitmanBossBar(UUID targetUuid) {
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            hitmanBossBars.remove(targetUuid);
            return;
        }
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        if (hitmanMgr.hasActiveContracts(targetUuid)) {
            updateHitmanBossBar(target);
        } else {
            removeHitmanBossBar(target);
        }
    }

    // ── Player Login / Quit ──────────────────────────────────────────────

    /**
     * Called on player login — show BossBars if they have active bounties or contracts,
     * and deliver any pending notifications after a short delay.
     */
    public void onPlayerLogin(Player player) {
        UUID uuid = player.getUniqueId();

        // Delay 1 tick to ensure player is fully loaded — BossBar setup
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                // Bounty BossBar
                if (bountyManager.hasBounty(uuid)) {
                    updateBossBar(player);
                }

                // Hitman contract BossBar
                HitmanManager hitmanMgr = plugin.getHitmanManager();
                if (hitmanMgr.hasActiveContracts(uuid)) {
                    updateHitmanBossBar(player);
                }
            }
        }.runTaskLater(plugin, 1L);

        // Deliver pending notifications after 2-second delay (avoid join spam)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                HitmanManager hitmanMgr = plugin.getHitmanManager();

                // 1. Pending proposals (player is a hitman and has proposals waiting)
                List<HitmanContract> pendingProposals = hitmanMgr.getPendingForHitman(uuid);
                for (HitmanContract c : pendingProposals) {
                    String targetName = getPlayerName(c.getTargetUuid());
                    String amount = economyManager.formatCurrency(c.getCurrentAmount());
                    int hours = c.getCurrentHours();
                    player.sendMessage(langManager.get(player, "hitman-proposal-received",
                            "target", targetName, "amount", amount, "hours", String.valueOf(hours)));
                }

                // 2. Counter-offers waiting for this player (as client)
                List<HitmanContract> clientContracts = hitmanMgr.getContractsForClient(uuid);
                for (HitmanContract c : clientContracts) {
                    if (c.getStatus().awaitingClient()) {
                        String amount = economyManager.formatCurrency(c.getCurrentAmount());
                        int hours = c.getCurrentHours();
                        player.sendMessage(langManager.get(player, "hitman-counter-offer",
                                "amount", amount, "hours", String.valueOf(hours)));
                    }
                }

                // 3. Rescission requests pending for this player
                // Check as hitman (client proposed rescission)
                List<HitmanContract> asHitman = hitmanMgr.getActiveContractsForHitman(uuid);
                for (HitmanContract c : asHitman) {
                    if (c.isRescissionPending() && "CLIENT".equals(c.getRescissionProposedBy())) {
                        int clientPct = c.getRescissionClientPercent();
                        int hitmanPct = 100 - clientPct;
                        player.sendMessage(langManager.get(player, "hitman-rescission-proposed",
                                "client_percent", String.valueOf(clientPct),
                                "hitman_percent", String.valueOf(hitmanPct)));
                    }
                }
                // Check as client (hitman proposed rescission)
                for (HitmanContract c : clientContracts) {
                    if (c.getStatus().isActive() && c.isRescissionPending()
                            && "HITMAN".equals(c.getRescissionProposedBy())) {
                        int clientPct = c.getRescissionClientPercent();
                        int hitmanPct = 100 - clientPct;
                        player.sendMessage(langManager.get(player, "hitman-rescission-proposed",
                                "client_percent", String.valueOf(clientPct),
                                "hitman_percent", String.valueOf(hitmanPct)));
                    }
                }

                // 4. Bounty notification reminder (if they have a bounty on their head)
                if (bountyManager.hasBounty(uuid)) {
                    long total = bountyManager.getTotalBounty(uuid);
                    String formatted = economyManager.formatCurrency(total);
                    player.sendMessage(langManager.get(player, "bounty-login-reminder", "amount", formatted));
                }
            }
        }.runTaskLater(plugin, 40L); // 2-second delay
    }

    /**
     * Called on player quit — clean up all BossBar references.
     */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();

        BossBar bountyBar = activeBossBars.remove(uuid);
        if (bountyBar != null) {
            player.hideBossBar(bountyBar);
        }

        BossBar hitmanBar = hitmanBossBars.remove(uuid);
        if (hitmanBar != null) {
            player.hideBossBar(hitmanBar);
        }
    }

    // ── Bounty Placed Notifications ──────────────────────────────────────

    /**
     * Notify all relevant parties when a bounty is placed.
     */
    public void notifyBountyPlaced(UUID contributorUuid, UUID targetUuid,
                                   long amountCents, long totalCost, long newTotal) {
        String formattedAmount = economyManager.formatCurrency(amountCents);
        String formattedCost = economyManager.formatCurrency(totalCost);
        String formattedTotal = economyManager.formatCurrency(newTotal);
        String taxPercent = String.valueOf(Math.round(configManager.getTaxRate() * 100));

        Player contributor = Bukkit.getPlayer(contributorUuid);
        Player target = Bukkit.getPlayer(targetUuid);

        String targetName = target != null ? target.getName() : Bukkit.getOfflinePlayer(targetUuid).getName();
        String contributorName = contributor != null ? contributor.getName() : Bukkit.getOfflinePlayer(contributorUuid).getName();
        if (targetName == null) targetName = "Unknown";
        if (contributorName == null) contributorName = "Unknown";

        // Notify contributor
        if (contributor != null && contributor.isOnline()) {
            contributor.sendMessage(langManager.get(contributor, "bounty-placed",
                    "amount", formattedAmount,
                    "target", targetName,
                    "cost", formattedCost,
                    "tax_percent", taxPercent));
            playSound(contributor, configManager.getSoundBountyPlaced());
        }

        // Notify target
        if (target != null && target.isOnline()) {
            target.sendMessage(langManager.get(target, "bounty-on-you",
                    "player", contributorName,
                    "amount", formattedAmount,
                    "total", formattedTotal));
            playSound(target, configManager.getSoundBountyPlaced());
            updateBossBar(target);
        }
    }

    // ── Bounty Claimed Notifications ─────────────────────────────────────

    /**
     * Broadcast a bounty placement to ALL online players.
     * Called from BountyManager.placeBounty() after a successful economy withdraw.
     */
    public void broadcastBountyPlaced(UUID contributorUuid, UUID targetUuid,
                                       long amountCents, long newTotal) {
        if (!configManager.isBountyPlacedBroadcast()) return;

        String formattedAmount = economyManager.formatCurrency(amountCents);
        String formattedTotal = economyManager.formatCurrency(newTotal);
        String targetName = getPlayerName(targetUuid);
        String contributorName = getPlayerName(contributorUuid);

        Component broadcast = langManager.getGlobal("bounty-placed-broadcast",
                "player", contributorName,
                "amount", formattedAmount,
                "target", targetName,
                "total", formattedTotal);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(broadcast);
        }
    }

    // ── Bounty Claimed Notifications (continued) ─────────────────────────

    /**
     * Notify all relevant parties when a bounty is claimed.
     *
     * @param targetUuid the killed target
     * @param payouts    Map<killerUUID, amountReceived>
     * @param totalBounty the total bounty that was distributed
     * @param deathLocation the death location (for firework)
     */
    public void notifyBountyClaimed(UUID targetUuid, Map<UUID, Long> payouts,
                                    long totalBounty, Location deathLocation) {
        String formattedTotal = economyManager.formatCurrency(totalBounty);
        String targetName = getPlayerName(targetUuid);

        // Notify each killer
        for (Map.Entry<UUID, Long> entry : payouts.entrySet()) {
            Player killer = Bukkit.getPlayer(entry.getKey());
            if (killer != null && killer.isOnline()) {
                double percent = (totalBounty > 0) ? ((double) entry.getValue() / totalBounty) * 100.0 : 100.0;
                killer.sendMessage(langManager.get(killer, "bounty-claimed-killer",
                        "amount", economyManager.formatCurrency(entry.getValue()),
                        "percent", String.format("%.0f", percent),
                        "target", targetName));
                playSound(killer, configManager.getSoundBountyClaimed());
            }
        }

        // Broadcast
        if (configManager.isBountyClaimedBroadcast()) {
            Component broadcast = langManager.getGlobal("bounty-claimed-broadcast",
                    "amount", formattedTotal,
                    "target", targetName);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(broadcast);
            }
        }

        // Remove BossBar from target
        removeBossBarByUuid(targetUuid);

        // Firework at death location
        spawnClaimFirework(deathLocation);
    }

    // ── Suspicious Death / Coords Reveal ─────────────────────────────────

    /**
     * Notify about a suspicious death (incremental count).
     */
    public void notifySuspiciousDeath(UUID targetUuid, int count) {
        String targetName = getPlayerName(targetUuid);
        String threshold = String.valueOf(configManager.getSuspiciousDeathThreshold());

        Component message = langManager.getGlobal("suspicious-death",
                "target", targetName,
                "count", String.valueOf(count),
                "threshold", threshold);

        // Send to online players with admin perm
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("serverbounties.admin")) {
                online.sendMessage(message);
            }
        }
    }

    /**
     * Reveal deathchest coordinates to ALL online players.
     */
    public void revealDeathchestCoords(UUID targetUuid, Location deathLocation, long bountyAmount) {
        if (!configManager.isRevealDeathchestCoords()) return;

        String targetName = getPlayerName(targetUuid);
        String formattedAmount = economyManager.formatCurrency(bountyAmount);

        for (Player online : Bukkit.getOnlinePlayers()) {
            Component message = langManager.get(online, "coords-revealed",
                    "target", targetName,
                    "x", String.valueOf(deathLocation.getBlockX()),
                    "y", String.valueOf(deathLocation.getBlockY()),
                    "z", String.valueOf(deathLocation.getBlockZ()),
                    "world", deathLocation.getWorld().getName(),
                    "amount", formattedAmount);
            online.sendMessage(message);
            playSound(online, configManager.getSoundCoordsRevealed());
        }
    }

    // ── Hit Notification (Action Bar) ────────────────────────────────────

    /** Cooldown map: attackerUUID:targetUUID → last notification time */
    private final Map<String, Long> hitNotifCooldowns = new ConcurrentHashMap<>();

    /**
     * Show action bar to an attacker when they hit a bounty target.
     * Respects cooldown to avoid spam.
     */
    public void showHitBountyNotification(Player attacker, UUID targetUuid, long bountyAmount) {
        String cooldownKey = attacker.getUniqueId() + ":" + targetUuid;
        long now = System.currentTimeMillis();
        Long lastShown = hitNotifCooldowns.get(cooldownKey);

        if (lastShown != null && (now - lastShown) < configManager.getHitNotificationCooldownMs()) {
            return; // Still in cooldown
        }
        hitNotifCooldowns.put(cooldownKey, now);

        String targetName = getPlayerName(targetUuid);
        String formattedAmount = economyManager.formatCurrency(bountyAmount);

        Component actionBar = langManager.get(attacker, "bounty-hit-notification",
                "target", targetName,
                "amount", formattedAmount);
        attacker.sendActionBar(actionBar);
        playSound(attacker, configManager.getSoundHitBountyTarget());
    }

    // ── Hitman Contract Notifications ───────────────────────────────────

    /**
     * Notify relevant parties when a hitman proposal is sent.
     */
    public void notifyHitmanProposalSent(UUID clientUuid, UUID hitmanUuid, UUID targetUuid,
                                          long amountCents, int hours) {
        String formattedAmount = economyManager.formatCurrency(amountCents);
        String targetName = getPlayerName(targetUuid);
        String hitmanName = getPlayerName(hitmanUuid);

        // Notify client
        Player client = Bukkit.getPlayer(clientUuid);
        if (client != null && client.isOnline()) {
            client.sendMessage(langManager.get(client, "hitman-proposal-sent",
                    "hitman", hitmanName));
            playSound(client, configManager.getSoundContractProposed());
        }

        // Notify hitman
        Player hitman = Bukkit.getPlayer(hitmanUuid);
        if (hitman != null && hitman.isOnline()) {
            hitman.sendMessage(langManager.get(hitman, "hitman-proposal-received",
                    "target", targetName, "amount", formattedAmount,
                    "hours", String.valueOf(hours)));
            playSound(hitman, configManager.getSoundContractProposed());
        }
    }

    /**
     * Notify when a contract is accepted (money withdrawn, timer starts).
     * Also shows BossBar to target and sends anonymous broadcast.
     */
    public void notifyHitmanContractAccepted(HitmanContract contract) {
        String targetName = getPlayerName(contract.getTargetUuid());
        String formattedAmount = economyManager.formatCurrency(contract.getCurrentAmount());
        String hours = String.valueOf(contract.getCurrentHours());

        Player hitman = Bukkit.getPlayer(contract.getHitmanUuid());
        if (hitman != null && hitman.isOnline()) {
            hitman.sendMessage(langManager.get(hitman, "hitman-accepted",
                    "target", targetName, "amount", formattedAmount, "hours", hours));
            playSound(hitman, configManager.getSoundContractAccepted());
        }

        Player client = Bukkit.getPlayer(contract.getClientUuid());
        if (client != null && client.isOnline()) {
            client.sendMessage(langManager.get(client, "hitman-accepted-client",
                    "amount", formattedAmount));
            playSound(client, configManager.getSoundContractAccepted());
        }

        // Show hitman BossBar to target
        Player target = Bukkit.getPlayer(contract.getTargetUuid());
        if (target != null && target.isOnline()) {
            updateHitmanBossBar(target);
        }
    }

    /**
     * Show action bar to an attacker when they hit a hitman contract target.
     * Respects the same cooldown mechanism as bounty hit notifications.
     */
    public void showHitContractNotification(Player attacker, UUID targetUuid, long prizeCents) {
        String cooldownKey = "h:" + attacker.getUniqueId() + ":" + targetUuid;
        long now = System.currentTimeMillis();
        Long lastShown = hitNotifCooldowns.get(cooldownKey);

        if (lastShown != null && (now - lastShown) < configManager.getHitNotificationCooldownMs()) {
            return; // Still in cooldown
        }
        hitNotifCooldowns.put(cooldownKey, now);

        String targetName = getPlayerName(targetUuid);
        String formattedPrize = economyManager.formatCurrency(prizeCents);

        Component actionBar = langManager.get(attacker, "hitman-hit-notification",
                "target", targetName,
                "prize", formattedPrize);
        attacker.sendActionBar(actionBar);
        playSound(attacker, configManager.getSoundHitBountyTarget());
    }

    /**
     * Notify relevant parties about hitman contract completions/cancellations
     * when the TARGET dies. Called from DeathListener on the main thread.
     */
    public void notifyHitmanContractResults(List<HitmanManager.ContractResult> results, Location deathLocation) {
        for (HitmanManager.ContractResult result : results) {
            HitmanContract contract = result.contract();

            String targetName = getPlayerName(contract.getTargetUuid());
            String formattedPrize = economyManager.formatCurrency(contract.getCurrentAmount());

            Player hitman = Bukkit.getPlayer(contract.getHitmanUuid());
            Player client = Bukkit.getPlayer(contract.getClientUuid());

            switch (result.outcome()) {
                case COMPLETED -> {
                    // Notify hitman
                    if (hitman != null && hitman.isOnline()) {
                        hitman.sendMessage(langManager.get(hitman, "hitman-contract-completed",
                                "target", targetName, "prize", formattedPrize));
                        playSound(hitman, configManager.getSoundContractCompleted());
                    }
                    // Notify client
                    if (client != null && client.isOnline()) {
                        client.sendMessage(langManager.get(client, "hitman-contract-completed-client",
                                "target", targetName));
                        playSound(client, configManager.getSoundContractCompleted());
                    }
                    // Firework at death location
                    spawnClaimFirework(deathLocation);
                }
                case CANCELLED_REFUND -> {
                    // Notify client (refund — someone else killed the target)
                    if (client != null && client.isOnline()) {
                        client.sendMessage(langManager.get(client, "hitman-target-died-other",
                                "target", targetName, "amount", formattedPrize));
                        playSound(client, configManager.getSoundContractCancelled());
                    }
                    // Notify hitman (contract cancelled)
                    if (hitman != null && hitman.isOnline()) {
                        hitman.sendMessage(langManager.get(hitman, "hitman-contract-cancelled-hitman",
                                "target", targetName));
                        playSound(hitman, configManager.getSoundContractCancelled());
                    }
                }
            }
        }

        // Refresh hitman BossBar for the target (remove if no more active contracts)
        if (!results.isEmpty()) {
            UUID targetUuid = results.getFirst().contract().getTargetUuid();
            refreshHitmanBossBar(targetUuid);
        }
    }

    // ── Hitman Contract Expiry Notifications ─────────────────────────────

    /**
     * Notify hitman and client when a contract expires (timer ran out).
     * Called from HitmanManager's expiry check on the main thread.
     */
    public void notifyContractExpired(HitmanContract contract) {
        String targetName = getPlayerName(contract.getTargetUuid());
        String formattedAmount = economyManager.formatCurrency(contract.getCurrentAmount());

        // Notify hitman — failed timeout
        Player hitman = Bukkit.getPlayer(contract.getHitmanUuid());
        if (hitman != null && hitman.isOnline()) {
            hitman.sendMessage(langManager.get(hitman, "hitman-failed-timeout",
                    "target", targetName));
            playSound(hitman, configManager.getSoundContractCancelled());
        }

        // Notify client — full refund
        Player client = Bukkit.getPlayer(contract.getClientUuid());
        if (client != null && client.isOnline()) {
            client.sendMessage(langManager.get(client, "hitman-refund-timeout",
                    "amount", formattedAmount));
            playSound(client, configManager.getSoundContractCancelled());
        }

        // Refresh hitman BossBar for target
        refreshHitmanBossBar(contract.getTargetUuid());
    }

    // ── Hitman Killed by Target Notifications ────────────────────────────

    /**
     * Notify all parties when the target kills the hitman.
     * Split: client gets partial refund, target gets reward.
     */
    public void notifyHitmanKilledByTarget(HitmanContract contract, long clientRefund, long targetReward) {
        String targetName = getPlayerName(contract.getTargetUuid());

        // Notify client — partial refund
        Player client = Bukkit.getPlayer(contract.getClientUuid());
        if (client != null && client.isOnline()) {
            client.sendMessage(langManager.get(client, "hitman-killed-by-target",
                    "target", targetName,
                    "refund", economyManager.formatCurrency(clientRefund)));
            playSound(client, configManager.getSoundContractCancelled());
        }

        // Notify target — reward for killing the hitman
        Player target = Bukkit.getPlayer(contract.getTargetUuid());
        if (target != null && target.isOnline()) {
            target.sendMessage(langManager.get(target, "hitman-target-reward",
                    "amount", economyManager.formatCurrency(targetReward)));
            playSound(target, configManager.getSoundContractCompleted());
        }

        // Refresh hitman BossBar for target
        refreshHitmanBossBar(contract.getTargetUuid());
    }

    // ── Anonymous Broadcast ──────────────────────────────────────────────

    /**
     * Send an anonymous contract notification to the target player ONLY.
     * No client name, no hitman name, no amount — just a warning.
     */
    public void broadcastAnonymousContract(UUID targetUuid) {
        if (!configManager.isAnonymousBroadcast()) return;

        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            Component message = langManager.get(target, "hitman-anonymous-broadcast",
                    "target", target.getName());
            target.sendMessage(message);
        }
    }

    // ── Stale Negotiation Notifications ──────────────────────────────────

    /**
     * Notify hitman and client when a negotiation is auto-cancelled due to inactivity.
     */
    public void notifyStaleNegotiationCancelled(HitmanContract contract) {
        String targetName = getPlayerName(contract.getTargetUuid());

        Player hitman = Bukkit.getPlayer(contract.getHitmanUuid());
        if (hitman != null && hitman.isOnline()) {
            hitman.sendMessage(langManager.get(hitman, "hitman-contract-cancelled-hitman",
                    "target", targetName));
        }

        Player client = Bukkit.getPlayer(contract.getClientUuid());
        if (client != null && client.isOnline()) {
            client.sendMessage(langManager.get(client, "hitman-contract-cancelled-hitman",
                    "target", targetName));
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private void playSound(Player player, String soundKey) {
        try {
            Key key = Key.key(soundKey);
            player.playSound(Sound.sound(key, Sound.Source.MASTER, 1.0f, 1.0f));
        } catch (Exception e) {
            plugin.debug("Failed to play sound '" + soundKey + "': " + e.getMessage());
        }
    }

    private void spawnClaimFirework(Location location) {
        if (location == null || location.getWorld() == null) return;

        // Run on main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Firework firework = location.getWorld().spawn(location, Firework.class);
                    FireworkMeta meta = firework.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .withColor(Color.ORANGE, Color.RED)
                            .withFade(Color.YELLOW)
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .flicker(true)
                            .trail(true)
                            .build());
                    meta.setPower(0); // Instant detonation
                    firework.setFireworkMeta(meta);
                    // Detonate immediately
                    firework.detonate();
                } catch (Exception e) {
                    plugin.debug("Failed to spawn claim firework: " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }

    private String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }

    private BossBar.Color parseBossBarColor(String colorName) {
        try {
            return BossBar.Color.valueOf(colorName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Color.RED;
        }
    }

    /**
     * Cleanup hit notification cooldowns (remove expired entries).
     * Called periodically.
     */
    public void cleanupCooldowns() {
        long cutoff = System.currentTimeMillis() - configManager.getHitNotificationCooldownMs() * 2;
        hitNotifCooldowns.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
