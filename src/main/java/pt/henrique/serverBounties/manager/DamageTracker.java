package pt.henrique.serverbounties.manager;

import pt.henrique.serverbounties.ServerBounties;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all player-vs-player damage in real time.
 * In-memory only — not persisted. Used to calculate proportional bounty distribution.
 */
public final class DamageTracker {

    private static final long CLEANUP_MAX_AGE_MS = 600_000; // 10 minutes

    private final ServerBounties plugin;

    /** target UUID → list of damage events */
    private final Map<UUID, List<DamageRecord>> damageHistory = new ConcurrentHashMap<>();

    public DamageTracker(ServerBounties plugin) {
        this.plugin = plugin;
    }

    // ── Record ───────────────────────────────────────────────────────────

    /**
     * A single damage event.
     */
    public record DamageRecord(UUID attackerUuid, double damage, long timestamp) {}

    // ── Track Damage ─────────────────────────────────────────────────────

    /**
     * Record damage dealt by a player to another player.
     * Called from EntityDamageByEntityEvent (MONITOR priority).
     */
    public void recordDamage(UUID target, UUID attacker, double damage) {
        // isFinite also rejects NaN, which "damage <= 0" lets through: one NaN record makes
        // the whole distribution NaN, and NaN sorts as the largest share, so a real killer
        // ends up last and takes the entire pot as the remainder.
        if (!Double.isFinite(damage) || damage <= 0) return;

        damageHistory.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new DamageRecord(attacker, damage, System.currentTimeMillis()));
    }

    // ── Query ────────────────────────────────────────────────────────────

    /**
     * Get damage distribution for a target within a time window.
     * Returns Map<attackerUUID, percentageOfTotalDamage> — values sum to 1.0.
     *
     * @param target   the target who was damaged
     * @param windowMs time window in milliseconds (e.g., 300000 for 5 min, 150000 for 2:30)
     * @return damage distribution map, or empty map if no damage was recorded
     */
    public Map<UUID, Double> getDamageDistribution(UUID target, long windowMs) {
        List<DamageRecord> records = damageHistory.get(target);
        if (records == null || records.isEmpty()) {
            return Collections.emptyMap();
        }

        long cutoff = System.currentTimeMillis() - windowMs;

        // Sum damage per attacker within the window
        Map<UUID, Double> damagePerAttacker = new HashMap<>();
        double totalDamage = 0;

        synchronized (records) {
            for (DamageRecord record : records) {
                if (record.timestamp() >= cutoff) {
                    damagePerAttacker.merge(record.attackerUuid(), record.damage(), Double::sum);
                    totalDamage += record.damage();
                }
            }
        }

        if (totalDamage <= 0 || damagePerAttacker.isEmpty()) {
            return Collections.emptyMap();
        }

        // Convert to percentages
        Map<UUID, Double> distribution = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : damagePerAttacker.entrySet()) {
            distribution.put(entry.getKey(), entry.getValue() / totalDamage);
        }

        return distribution;
    }

    /**
     * Check if a specific attacker dealt damage to a target within a time window.
     */
    public boolean hasDealtDamage(UUID target, UUID attacker, long windowMs) {
        List<DamageRecord> records = damageHistory.get(target);
        if (records == null) return false;

        long cutoff = System.currentTimeMillis() - windowMs;
        synchronized (records) {
            for (DamageRecord record : records) {
                if (record.attackerUuid().equals(attacker) && record.timestamp() >= cutoff) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clear all damage records for a target (e.g., after death processed).
     */
    public void clearRecords(UUID target) {
        damageHistory.remove(target);
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    /**
     * Clean old entries periodically (older than 10 minutes).
     * Should be called on a timer every 60 seconds.
     */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - CLEANUP_MAX_AGE_MS;
        int removed = 0;

        Iterator<Map.Entry<UUID, List<DamageRecord>>> it = damageHistory.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<DamageRecord>> entry = it.next();
            List<DamageRecord> records = entry.getValue();
            synchronized (records) {
                int before = records.size();
                records.removeIf(r -> r.timestamp() < cutoff);
                removed += (before - records.size());
                if (records.isEmpty()) {
                    it.remove();
                }
            }
        }

        if (removed > 0) {
            plugin.debug("DamageTracker cleanup: removed " + removed + " old records. "
                    + damageHistory.size() + " targets tracked.");
        }
    }
}

