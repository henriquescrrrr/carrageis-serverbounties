package pt.henrique.serverbounties.model;

import java.util.UUID;

/**
 * Per-player hitman profile settings.
 * When {@code available=true} the player appears in the hitman board
 * and can receive new contract proposals.
 * Active contracts continue regardless of availability toggle.
 */
public final class HitmanSettings {

    private final UUID uuid;
    private boolean available;
    private long minPriceCents;
    private int minHours;
    private int maxHours;
    private int maxConcurrent;

    public HitmanSettings(UUID uuid, boolean available, long minPriceCents,
                          int minHours, int maxHours, int maxConcurrent) {
        this.uuid = uuid;
        this.available = available;
        this.minPriceCents = minPriceCents;
        this.minHours = minHours;
        this.maxHours = maxHours;
        this.maxConcurrent = maxConcurrent;
    }

    /** Create default settings for a player using server-configured defaults. */
    public static HitmanSettings createDefault(UUID uuid, long defaultMinPrice,
                                                int defaultMinHours, int defaultMaxHours,
                                                int defaultMaxConcurrent) {
        return new HitmanSettings(uuid, false, defaultMinPrice,
                defaultMinHours, defaultMaxHours, defaultMaxConcurrent);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public UUID getUuid() { return uuid; }
    public boolean isAvailable() { return available; }
    public long getMinPriceCents() { return minPriceCents; }
    public int getMinHours() { return minHours; }
    public int getMaxHours() { return maxHours; }
    public int getMaxConcurrent() { return maxConcurrent; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setAvailable(boolean available) { this.available = available; }
    public void setMinPriceCents(long minPriceCents) { this.minPriceCents = minPriceCents; }
    public void setMinHours(int minHours) { this.minHours = minHours; }
    public void setMaxHours(int maxHours) { this.maxHours = maxHours; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
}

