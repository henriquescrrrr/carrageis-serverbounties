package pt.henrique.serverbounties.model;

import java.util.UUID;

/**
 * Represents a hitman contract through its full lifecycle.
 *
 * Flow: PENDING → negotiation (counter-offers) → ACCEPTED → outcome
 * Money is only withdrawn from the client when status transitions to ACCEPTED.
 */
public final class HitmanContract {

    private final int id;
    private final UUID clientUuid;
    private final UUID hitmanUuid;
    private final UUID targetUuid;
    private final long amountCents;          // original proposed amount
    private final int proposedHours;         // original proposed hours
    private final long createdAt;

    // status is volatile + mutated only under this contract's monitor for terminal
    // transitions (see tryTerminate) — this closes the check-then-act double-payout race
    // between the main thread (death handling) and the async expiry/stale timer.
    private volatile ContractStatus status;
    private long updatedAt;
    private long acceptedAt;                 // 0 = not yet accepted
    private long expiresAt;                  // 0 = not set
    private long completedAt;                // 0 = not terminated

    // Counter-offer fields (latest)
    private long counterValueCents;          // 0 = no counter
    private int counterHours;                // 0 = no counter
    private String counterFrom;              // "CLIENT" or "HITMAN", null = no counter

    // Rescission negotiation fields
    private int rescissionClientPercent;     // 0-100
    private String rescissionProposedBy;     // "CLIENT" or "HITMAN", null = none
    private volatile boolean rescissionPending;

    // ── Constructors ─────────────────────────────────────────────────────

    /** Constructor for creating a new proposal. */
    public HitmanContract(int id, UUID clientUuid, UUID hitmanUuid, UUID targetUuid,
                          long amountCents, int proposedHours) {
        this.id = id;
        this.clientUuid = clientUuid;
        this.hitmanUuid = hitmanUuid;
        this.targetUuid = targetUuid;
        this.amountCents = amountCents;
        this.proposedHours = proposedHours;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.status = ContractStatus.PENDING;
    }

    /** Full constructor for loading from database. */
    public HitmanContract(int id, UUID clientUuid, UUID hitmanUuid, UUID targetUuid,
                          long amountCents, ContractStatus status, int proposedHours,
                          long createdAt, long updatedAt, long acceptedAt, long expiresAt,
                          long completedAt, long counterValueCents, int counterHours,
                          String counterFrom, int rescissionClientPercent,
                          String rescissionProposedBy, boolean rescissionPending) {
        this.id = id;
        this.clientUuid = clientUuid;
        this.hitmanUuid = hitmanUuid;
        this.targetUuid = targetUuid;
        this.amountCents = amountCents;
        this.status = status;
        this.proposedHours = proposedHours;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.acceptedAt = acceptedAt;
        this.expiresAt = expiresAt;
        this.completedAt = completedAt;
        this.counterValueCents = counterValueCents;
        this.counterHours = counterHours;
        this.counterFrom = counterFrom;
        this.rescissionClientPercent = rescissionClientPercent;
        this.rescissionProposedBy = rescissionProposedBy;
        this.rescissionPending = rescissionPending;
    }

    // ── Current Terms ────────────────────────────────────────────────────

    /** The effective amount — counter-offer value if one exists, else original. */
    public long getCurrentAmount() {
        return counterValueCents > 0 ? counterValueCents : amountCents;
    }

    /** The effective hours — counter-offer hours if one exists, else original. */
    public int getCurrentHours() {
        return counterHours > 0 ? counterHours : proposedHours;
    }

    /** Remaining time in milliseconds (0 if not active or no expiry). */
    public long getRemainingMs() {
        if (expiresAt <= 0 || !status.isActive()) return 0;
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    /** True if the contract timer has passed. */
    public boolean isExpired() {
        return status.isActive() && expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /** True if a negotiation has gone stale (no activity for the given window). */
    public boolean isStale(long staleWindowMs) {
        return status.isNegotiating() && (System.currentTimeMillis() - updatedAt) > staleWindowMs;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public int getId() { return id; }
    public UUID getClientUuid() { return clientUuid; }
    public UUID getHitmanUuid() { return hitmanUuid; }
    public UUID getTargetUuid() { return targetUuid; }
    public long getAmountCents() { return amountCents; }
    public int getProposedHours() { return proposedHours; }
    public long getCreatedAt() { return createdAt; }
    public ContractStatus getStatus() { return status; }
    public long getUpdatedAt() { return updatedAt; }
    public long getAcceptedAt() { return acceptedAt; }
    public long getExpiresAt() { return expiresAt; }
    public long getCompletedAt() { return completedAt; }
    public long getCounterValueCents() { return counterValueCents; }
    public int getCounterHours() { return counterHours; }
    public String getCounterFrom() { return counterFrom; }
    public int getRescissionClientPercent() { return rescissionClientPercent; }
    public int getRescissionHitmanPercent() { return 100 - rescissionClientPercent; }
    public String getRescissionProposedBy() { return rescissionProposedBy; }
    public boolean isRescissionPending() { return rescissionPending; }

    // ── Setters (for state transitions — called by HitmanManager) ────────

    public void setStatus(ContractStatus status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Atomically move this contract from ACTIVE (ACCEPTED) to a terminal state.
     * Returns {@code true} only for the caller that actually performed the transition;
     * a concurrent caller (e.g. the async expiry timer racing the main-thread death
     * handler) sees {@code false} and must NOT run its payout/refund. This is the
     * compare-and-swap that prevents double payment / money duplication.
     *
     * @param terminal the terminal status to set (must not be active/negotiating)
     * @return true if this call won the transition; false if it was already terminated
     */
    public synchronized boolean tryTerminate(ContractStatus terminal) {
        if (!this.status.isActive()) {
            return false;
        }
        this.status = terminal;
        long now = System.currentTimeMillis();
        this.updatedAt = now;
        this.completedAt = now;
        return true;
    }

    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public void setAcceptedAt(long acceptedAt) { this.acceptedAt = acceptedAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public void setCounterOffer(long valueCents, int hours, String from) {
        this.counterValueCents = valueCents;
        this.counterHours = hours;
        this.counterFrom = from;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clearCounterOffer() {
        this.counterValueCents = 0;
        this.counterHours = 0;
        this.counterFrom = null;
    }

    public void setRescission(int clientPercent, String proposedBy) {
        this.rescissionClientPercent = clientPercent;
        this.rescissionProposedBy = proposedBy;
        this.rescissionPending = true;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clearRescission() {
        this.rescissionClientPercent = 0;
        this.rescissionProposedBy = null;
        this.rescissionPending = false;
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "HitmanContract{id=" + id + ", client=" + clientUuid +
                ", hitman=" + hitmanUuid + ", target=" + targetUuid +
                ", amount=" + amountCents + ", status=" + status +
                ", hours=" + proposedHours + "}";
    }
}

