package pt.henrique.serverbounties.manager;

import java.util.UUID;

/**
 * Represents a single bounty contribution entry.
 */
public final class BountyEntry {

    private final int id;
    private final UUID targetUuid;
    private final UUID contributorUuid;
    private final long amountCents;
    private final long createdAt;

    public BountyEntry(int id, UUID targetUuid, UUID contributorUuid, long amountCents, long createdAt) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.contributorUuid = contributorUuid;
        this.amountCents = amountCents;
        this.createdAt = createdAt;
    }

    public int getId() { return id; }
    public UUID getTargetUuid() { return targetUuid; }
    public UUID getContributorUuid() { return contributorUuid; }
    public long getAmountCents() { return amountCents; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "BountyEntry{id=" + id +
                ", target=" + targetUuid +
                ", contributor=" + contributorUuid +
                ", amount=" + amountCents +
                ", created=" + createdAt + "}";
    }
}

