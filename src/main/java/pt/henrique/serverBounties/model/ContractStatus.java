package pt.henrique.serverbounties.model;

/**
 * Status lifecycle for hitman contracts.
 *
 * Pre-accept states (no money involved):
 *   PENDING → COUNTER_FROM_HITMAN ↔ COUNTER_FROM_CLIENT → ACCEPTED | DECLINED | CANCELLED_BY_CLIENT
 *
 * Post-accept states (money escrowed from client):
 *   ACCEPTED → COMPLETED | FAILED_TIMEOUT | FAILED_HITMAN_KILLED | CANCELLED_TARGET_DIED | CANCELLED_RESCISSION
 */
public enum ContractStatus {
    PENDING,
    COUNTER_FROM_HITMAN,
    COUNTER_FROM_CLIENT,
    ACCEPTED,
    COMPLETED,
    FAILED_TIMEOUT,
    FAILED_HITMAN_KILLED,
    CANCELLED_TARGET_DIED,
    CANCELLED_BY_CLIENT,
    CANCELLED_RESCISSION,
    DECLINED;

    /** True only for timer-running contracts. */
    public boolean isActive() {
        return this == ACCEPTED;
    }

    /** True for pre-accept negotiation states. */
    public boolean isNegotiating() {
        return this == PENDING || this == COUNTER_FROM_HITMAN || this == COUNTER_FROM_CLIENT;
    }

    /** True for any final state — contract is done. */
    public boolean isTerminal() {
        return !isActive() && !isNegotiating();
    }

    /** Whether this state requires the hitman's response. */
    public boolean awaitingHitman() {
        return this == PENDING || this == COUNTER_FROM_CLIENT;
    }

    /** Whether this state requires the client's response. */
    public boolean awaitingClient() {
        return this == COUNTER_FROM_HITMAN;
    }
}

