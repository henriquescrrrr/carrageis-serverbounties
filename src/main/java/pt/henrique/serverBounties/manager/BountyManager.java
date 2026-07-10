package pt.henrique.serverbounties.manager;

import org.bukkit.Bukkit;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.storage.DatabaseManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages all bounty operations with an in-memory cache backed by SQLite.
 * Cache is loaded from DB on startup. All reads are from cache (O(1) for DeathListener).
 * Writes go to DB async and update the cache immediately.
 */
public final class BountyManager {

    private final ServerBounties plugin;
    private final ConfigManager configManager;
    private final EconomyManager economyManager;
    private final DatabaseManager databaseManager;

    /** target UUID → list of bounty entries (thread-safe) */
    private final Map<UUID, List<BountyEntry>> cache = new ConcurrentHashMap<>();

    public BountyManager(ServerBounties plugin, ConfigManager configManager,
                         EconomyManager economyManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.economyManager = economyManager;
        this.databaseManager = databaseManager;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Load all bounty entries from the database into the cache.
     * Returns a future that completes when the cache is ready.
     */
    public CompletableFuture<Void> loadCache() {
        return databaseManager.loadAllBountyEntries().thenAccept(entries -> {
            cache.clear();
            for (BountyEntry entry : entries) {
                cache.computeIfAbsent(entry.getTargetUuid(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(entry);
            }
            plugin.debug("BountyManager cache loaded: " + cache.size() + " targets, "
                    + entries.size() + " entries total.");
        });
    }

    // ── Place Bounty ─────────────────────────────────────────────────────

    /**
     * Place a bounty contribution. Withdraws from contributor (amount + tax) asynchronously.
     *
     * @param contributor the player placing the bounty
     * @param target      the bounty target
     * @param amountCents the raw bounty amount in cents (before tax)
     * @return a future with the result
     */
    public CompletableFuture<PlaceBountyResult> placeBounty(UUID contributor, UUID target, long amountCents) {
        // Validation
        if (contributor.equals(target)) {
            return CompletableFuture.completedFuture(PlaceBountyResult.SELF);
        }

        if (amountCents < configManager.getMinAmount()) {
            return CompletableFuture.completedFuture(PlaceBountyResult.BELOW_MIN);
        }

        if (amountCents > configManager.getMaxAmountPerContribution()) {
            return CompletableFuture.completedFuture(PlaceBountyResult.ABOVE_MAX);
        }

        long currentTotal = getTotalBounty(target);
        if (currentTotal + amountCents > configManager.getMaxTotalPerTarget()) {
            return CompletableFuture.completedFuture(PlaceBountyResult.CAP_REACHED);
        }

        // Same faction check (sync — hooks are reflection-based but fast)
        if (!configManager.isAllowSameFaction() && plugin.getFactionsHook().areInSameFaction(contributor, target)) {
            return CompletableFuture.completedFuture(PlaceBountyResult.SAME_FACTION);
        }

        long tax = Math.round(amountCents * configManager.getTaxRate());
        long totalCost = amountCents + tax;

        // Async: check balance → withdraw → save to DB → update cache
        return economyManager.getBalanceCents(contributor).thenCompose(balance -> {
            if (balance < totalCost) {
                return CompletableFuture.completedFuture(PlaceBountyResult.NOT_ENOUGH_MONEY);
            }

            return economyManager.withdraw(contributor, totalCost, "Bounty on target + tax")
                    .thenCompose(success -> {
                        if (!success) {
                            return CompletableFuture.completedFuture(PlaceBountyResult.ECONOMY_ERROR);
                        }

                        return databaseManager.insertBountyEntry(target, contributor, amountCents)
                                .thenApply(entryId -> {
                                    if (entryId < 0) {
                                        // DB error — refund
                                        economyManager.deposit(contributor, totalCost, "Bounty refund (DB error)");
                                        return PlaceBountyResult.ECONOMY_ERROR;
                                    }

                                    // Update cache
                                    BountyEntry entry = new BountyEntry(entryId, target, contributor,
                                            amountCents, System.currentTimeMillis());
                                    cache.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>()))
                                            .add(entry);

                                    plugin.debug("Bounty placed: " + contributor + " → " + target
                                            + " for " + amountCents + " (tax: " + tax + ")");

                                    // Broadcast bounty placement to all online players
                                    if (amountCents >= configManager.getBroadcastThreshold()) {
                                        long newTotal = getTotalBounty(target);
                                        Bukkit.getScheduler().runTask(plugin, () ->
                                                plugin.getNotificationManager().broadcastBountyPlaced(
                                                        contributor, target, amountCents, newTotal));
                                    }

                                    return PlaceBountyResult.SUCCESS;
                                });
                    });
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Error placing bounty: " + ex.getMessage());
            return PlaceBountyResult.ECONOMY_ERROR;
        });
    }

    // ── Cancel Contribution ──────────────────────────────────────────────

    /**
     * Cancel a specific contribution (refund to contributor).
     * Blocked if target is combat-tagged and config says so.
     */
    public CompletableFuture<Boolean> cancelContribution(UUID contributor, UUID target, int entryId) {
        // Check combat tag
        if (configManager.isCancelBlockedInCombat() && plugin.getCombatLogHook().isInCombat(target)) {
            return CompletableFuture.completedFuture(false);
        }

        // Find entry in cache
        List<BountyEntry> entries = cache.get(target);
        if (entries == null) return CompletableFuture.completedFuture(false);

        BountyEntry toRemove = null;
        synchronized (entries) {
            for (BountyEntry entry : entries) {
                if (entry.getId() == entryId && entry.getContributorUuid().equals(contributor)) {
                    toRemove = entry;
                    break;
                }
            }
        }

        if (toRemove == null) return CompletableFuture.completedFuture(false);

        final BountyEntry removed = toRemove;

        // Remove from cache immediately
        synchronized (entries) {
            entries.remove(removed);
            if (entries.isEmpty()) {
                cache.remove(target);
            }
        }

        // Async: delete from DB + refund
        return databaseManager.deleteBountyEntry(entryId).thenCompose(success -> {
            if (!success) {
                // Re-add to cache if DB delete failed
                cache.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(removed);
                return CompletableFuture.completedFuture(false);
            }

            return economyManager.deposit(contributor, removed.getAmountCents(),
                    "Bounty contribution cancelled (refund)")
                    .thenApply(deposited -> {
                        if (!deposited) {
                            plugin.getLogger().warning("Failed to refund " + removed.getAmountCents()
                                    + " to " + contributor + " for cancelled bounty entry " + entryId);
                        }
                        return true; // Entry was removed even if deposit failed (log the issue)
                    });
        });
    }

    // ── Claim Bounty ─────────────────────────────────────────────────────

    /**
     * Claim/distribute the bounty when the target is killed.
     * Distributes proportionally by damage dealt.
     *
     * @param target             the killed target
     * @param damageDistribution Map<killerUUID, percentage (0.0–1.0)>
     * @return future with Map<killerUUID, amountReceived> for notifications
     */
    public CompletableFuture<Map<UUID, Long>> claimBounty(UUID target, Map<UUID, Double> damageDistribution) {
        long totalBounty = getTotalBounty(target);
        if (totalBounty <= 0 || damageDistribution.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        // Remove from cache immediately
        List<BountyEntry> removed = cache.remove(target);
        if (removed == null || removed.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        // Calculate per-killer payouts
        Map<UUID, Long> payouts = new HashMap<>();
        long distributed = 0;

        List<Map.Entry<UUID, Double>> sortedKillers = new ArrayList<>(damageDistribution.entrySet());
        sortedKillers.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());

        for (int i = 0; i < sortedKillers.size(); i++) {
            Map.Entry<UUID, Double> killer = sortedKillers.get(i);
            long payout;
            if (i == sortedKillers.size() - 1) {
                // Last killer gets the remainder to avoid rounding issues
                payout = totalBounty - distributed;
            } else {
                payout = Math.round(totalBounty * killer.getValue());
            }
            if (payout > 0) {
                payouts.put(killer.getKey(), payout);
                distributed += payout;
            }
        }

        // Delete all entries from DB
        CompletableFuture<Boolean> dbDelete = databaseManager.deleteAllBountyEntries(target);

        // Deposit to each killer (independent async operations)
        List<CompletableFuture<Void>> deposits = new ArrayList<>();
        for (Map.Entry<UUID, Long> payout : payouts.entrySet()) {
            deposits.add(
                    economyManager.deposit(payout.getKey(), payout.getValue(),
                            "Bounty claim for killing " + target)
                            .thenAccept(success -> {
                                if (!success) {
                                    plugin.getLogger().warning("Failed to deposit bounty payout of "
                                            + payout.getValue() + " to " + payout.getKey());
                                }
                            })
            );
        }

        // Wait for all deposits and the DB delete
        return CompletableFuture.allOf(
                dbDelete.thenAccept(v -> {}),
                CompletableFuture.allOf(deposits.toArray(new CompletableFuture[0]))
        ).thenApply(v -> payouts).exceptionally(ex -> {
            plugin.getLogger().warning("Error distributing bounty: " + ex.getMessage());
            return payouts; // Return whatever we calculated, deposits may have partially succeeded
        });
    }

    // ── Query Methods (Cache-based, O(1)) ────────────────────────────────

    /**
     * Check if a target has an active bounty.
     */
    public boolean hasBounty(UUID target) {
        List<BountyEntry> entries = cache.get(target);
        return entries != null && !entries.isEmpty();
    }

    /**
     * Get the total bounty amount for a target (from cache).
     */
    public long getTotalBounty(UUID target) {
        List<BountyEntry> entries = cache.get(target);
        if (entries == null || entries.isEmpty()) return 0;
        synchronized (entries) {
            return entries.stream().mapToLong(BountyEntry::getAmountCents).sum();
        }
    }

    /**
     * Get all entries for a target.
     */
    public List<BountyEntry> getEntries(UUID target) {
        List<BountyEntry> entries = cache.get(target);
        if (entries == null) return Collections.emptyList();
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * Get entries by a specific contributor for a target.
     */
    public List<BountyEntry> getEntriesByContributor(UUID contributor, UUID target) {
        List<BountyEntry> entries = cache.get(target);
        if (entries == null) return Collections.emptyList();
        synchronized (entries) {
            return entries.stream()
                    .filter(e -> e.getContributorUuid().equals(contributor))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Get all bounties: target → total amount. Sorted by total descending.
     */
    public Map<UUID, Long> getAllBounties() {
        Map<UUID, Long> result = new LinkedHashMap<>();
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>();

        for (Map.Entry<UUID, List<BountyEntry>> entry : cache.entrySet()) {
            List<BountyEntry> entries = entry.getValue();
            if (entries != null && !entries.isEmpty()) {
                synchronized (entries) {
                    long total = entries.stream().mapToLong(BountyEntry::getAmountCents).sum();
                    sorted.add(Map.entry(entry.getKey(), total));
                }
            }
        }

        sorted.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
        for (Map.Entry<UUID, Long> e : sorted) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    /**
     * Get all targets that a contributor has placed bounties on.
     * Returns Map<targetUUID, List<BountyEntry>>
     */
    public Map<UUID, List<BountyEntry>> getContributedBounties(UUID contributor) {
        Map<UUID, List<BountyEntry>> result = new HashMap<>();
        for (Map.Entry<UUID, List<BountyEntry>> entry : cache.entrySet()) {
            List<BountyEntry> entries = entry.getValue();
            if (entries == null) continue;
            List<BountyEntry> contributed;
            synchronized (entries) {
                contributed = entries.stream()
                        .filter(e -> e.getContributorUuid().equals(contributor))
                        .collect(Collectors.toList());
            }
            if (!contributed.isEmpty()) {
                result.put(entry.getKey(), contributed);
            }
        }
        return result;
    }

    /**
     * Get the number of unique contributors for a target.
     */
    public int getContributorCount(UUID target) {
        List<BountyEntry> entries = cache.get(target);
        if (entries == null) return 0;
        synchronized (entries) {
            return (int) entries.stream()
                    .map(BountyEntry::getContributorUuid)
                    .distinct()
                    .count();
        }
    }

    // ── Admin ────────────────────────────────────────────────────────────

    /**
     * Admin: remove all bounties on a target (no refund).
     */
    public CompletableFuture<Boolean> removeBounty(UUID target) {
        cache.remove(target);
        return databaseManager.deleteAllBountyEntries(target);
    }

    /**
     * Admin: remove all bounties on a target and refund all contributors.
     */
    public CompletableFuture<Boolean> removeBountyWithRefund(UUID target) {
        List<BountyEntry> entries = cache.remove(target);
        if (entries == null || entries.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        // Refund each contributor
        List<CompletableFuture<Void>> refunds = new ArrayList<>();
        synchronized (entries) {
            for (BountyEntry entry : entries) {
                refunds.add(
                        economyManager.deposit(entry.getContributorUuid(), entry.getAmountCents(),
                                "Bounty refund (admin removed)")
                                .thenAccept(success -> {
                                    if (!success) {
                                        plugin.getLogger().warning("Failed to refund " + entry.getAmountCents()
                                                + " to " + entry.getContributorUuid());
                                    }
                                })
                );
            }
        }

        return databaseManager.deleteAllBountyEntries(target)
                .thenCombine(
                        CompletableFuture.allOf(refunds.toArray(new CompletableFuture[0])),
                        (dbOk, v) -> dbOk
                );
    }

    // ── Result Enum ──────────────────────────────────────────────────────

    public enum PlaceBountyResult {
        SUCCESS,
        SELF,
        SAME_FACTION,
        BELOW_MIN,
        ABOVE_MAX,
        CAP_REACHED,
        NOT_ENOUGH_MONEY,
        ECONOMY_ERROR,
        TARGET_OFFLINE
    }
}

