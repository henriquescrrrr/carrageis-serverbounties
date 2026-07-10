package pt.henrique.serverbounties.manager;

import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks suspicious deaths per player for anti-exploit.
 * In-memory only — resets on server restart.
 *
 * A "suspicious death" is when a bounty target dies but no valid killers exist
 * (e.g., suicide, environmental, all damage from faction/self).
 *
 * If a target accumulates too many suspicious deaths within a time window,
 * their deathchest coordinates are revealed to ALL online players.
 */
public final class SuspiciousDeathTracker {

    private final ServerBounties plugin;
    private final ConfigManager configManager;

    /** target UUID → list of timestamps of suspicious deaths */
    private final Map<UUID, List<Long>> deaths = new ConcurrentHashMap<>();

    public SuspiciousDeathTracker(ServerBounties plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Record a suspicious death for a target.
     */
    public void recordSuspiciousDeath(UUID target) {
        deaths.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(System.currentTimeMillis());
        plugin.debug("Suspicious death recorded for " + target
                + ". Count: " + getRecentCount(target));
    }

    /**
     * Check if the suspicious death threshold has been reached for this target.
     * Cleans old entries before checking.
     *
     * @return true if the number of suspicious deaths in the configured window
     *         meets or exceeds the threshold
     */
    public boolean shouldRevealCoords(UUID target) {
        cleanOldEntries(target);
        int count = getRecentCount(target);
        return count >= configManager.getSuspiciousDeathThreshold();
    }

    /**
     * Get the count of suspicious deaths within the time window.
     */
    public int getRecentCount(UUID target) {
        List<Long> timestamps = deaths.get(target);
        if (timestamps == null || timestamps.isEmpty()) return 0;

        long cutoff = System.currentTimeMillis() - configManager.getSuspiciousDeathWindowMs();
        synchronized (timestamps) {
            return (int) timestamps.stream().filter(t -> t >= cutoff).count();
        }
    }

    /**
     * Remove entries older than the configured time window.
     */
    public void cleanOldEntries(UUID target) {
        List<Long> timestamps = deaths.get(target);
        if (timestamps == null) return;

        long cutoff = System.currentTimeMillis() - configManager.getSuspiciousDeathWindowMs();
        synchronized (timestamps) {
            timestamps.removeIf(t -> t < cutoff);
            if (timestamps.isEmpty()) {
                deaths.remove(target);
            }
        }
    }

    /**
     * Periodic cleanup of all tracked players. Run every few minutes.
     */
    public void cleanupAll() {
        long cutoff = System.currentTimeMillis() - configManager.getSuspiciousDeathWindowMs();
        int removed = 0;

        Iterator<Map.Entry<UUID, List<Long>>> it = deaths.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Long>> entry = it.next();
            List<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                int before = timestamps.size();
                timestamps.removeIf(t -> t < cutoff);
                removed += (before - timestamps.size());
                if (timestamps.isEmpty()) {
                    it.remove();
                }
            }
        }

        if (removed > 0) {
            plugin.debug("SuspiciousDeathTracker cleanup: removed " + removed + " old entries.");
        }
    }
}

