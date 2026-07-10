package pt.henrique.serverbounties.manager;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.model.ContractStatus;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.model.HitmanSettings;
import pt.henrique.serverbounties.storage.DatabaseManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages the full hitman contract lifecycle with negotiation, rescission,
 * expiry timers, and per-player hitman settings.
 *
 * Money is withdrawn from the client ONLY when the contract is ACCEPTED.
 * All reads are from cache; writes go to DB async.
 */
public final class HitmanManager {

    private final ServerBounties plugin;
    private final ConfigManager configManager;
    private final EconomyManager economyManager;
    private final DatabaseManager databaseManager;

    /** All non-terminal contracts indexed by ID */
    private final Map<Integer, HitmanContract> cacheById = new ConcurrentHashMap<>();
    /** target UUID → list of cached contract IDs */
    private final Map<UUID, List<Integer>> contractsByTarget = new ConcurrentHashMap<>();
    /** hitman UUID → list of cached contract IDs */
    private final Map<UUID, List<Integer>> contractsByHitman = new ConcurrentHashMap<>();
    /** client UUID → list of cached contract IDs */
    private final Map<UUID, List<Integer>> contractsByClient = new ConcurrentHashMap<>();
    /** Per-player hitman settings */
    private final Map<UUID, HitmanSettings> settingsCache = new ConcurrentHashMap<>();

    private int expiryTaskId = -1;

    public HitmanManager(ServerBounties plugin, ConfigManager configManager,
                         EconomyManager economyManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.economyManager = economyManager;
        this.databaseManager = databaseManager;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    public CompletableFuture<Void> loadCache() {
        CompletableFuture<Void> contractsFuture = databaseManager.loadAllNonTerminalContracts().thenAccept(contracts -> {
            cacheById.clear();
            contractsByTarget.clear();
            contractsByHitman.clear();
            contractsByClient.clear();

            for (HitmanContract c : contracts) {
                addToCache(c);
            }
            plugin.debug("HitmanManager cache loaded: " + cacheById.size() + " non-terminal contracts.");
        });

        CompletableFuture<Void> settingsFuture = databaseManager.loadAllHitmanSettings().thenAccept(settings -> {
            settingsCache.clear();
            for (HitmanSettings s : settings) {
                settingsCache.put(s.getUuid(), s);
            }
            plugin.debug("HitmanManager settings loaded: " + settingsCache.size() + " profiles.");
        });

        return CompletableFuture.allOf(contractsFuture, settingsFuture);
    }

    /** Start the repeating task that checks for expired contracts and stale negotiations. */
    public void startExpiryTimer() {
        this.expiryTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredContracts();
                checkStaleNegotiations();
            }
        }.runTaskTimerAsynchronously(plugin, 1200L, 1200L).getTaskId();
    }

    public void stopExpiryTimer() {
        if (expiryTaskId != -1) {
            Bukkit.getScheduler().cancelTask(expiryTaskId);
            expiryTaskId = -1;
        }
    }

    // ── Contract Creation ────────────────────────────────────────────────

    public CompletableFuture<CreateResult> createProposal(UUID client, UUID hitman, UUID target,
                                                           long amountCents, int hours) {
        if (client.equals(target)) return done(CreateResult.SELF_TARGET);
        if (client.equals(hitman)) return done(CreateResult.SELF_HIRE);
        if (hitman.equals(target)) return done(CreateResult.HITMAN_IS_TARGET);

        if (!configManager.isAllowSameFaction() && plugin.getFactionsHook().areInSameFaction(client, target)) {
            return done(CreateResult.SAME_FACTION);
        }

        HitmanSettings settings = getSettings(hitman);
        if (!settings.isAvailable()) return done(CreateResult.HITMAN_UNAVAILABLE);
        if (amountCents < settings.getMinPriceCents()) return done(CreateResult.BELOW_MIN_PRICE);
        if (hours < settings.getMinHours() || hours > settings.getMaxHours()) return done(CreateResult.INVALID_HOURS);
        if (getActiveContractCount(hitman) >= settings.getMaxConcurrent()) return done(CreateResult.HITMAN_MAX_CONTRACTS);

        return economyManager.getBalanceCents(client).thenCompose(balance -> {
            if (balance < amountCents) return done(CreateResult.NOT_ENOUGH_MONEY);

            return databaseManager.insertContract(client, hitman, target, amountCents, hours)
                    .thenApply(contractId -> {
                        if (contractId < 0) return CreateResult.ECONOMY_ERROR;

                        HitmanContract contract = new HitmanContract(contractId, client, hitman, target,
                                amountCents, hours);
                        addToCache(contract);

                        plugin.debug("Hitman proposal created: " + client + " → " + hitman
                                + " to kill " + target + " for " + amountCents + " (" + hours + "h)");
                        return CreateResult.SUCCESS;
                    });
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Error creating hitman proposal: " + ex.getMessage());
            return CreateResult.ECONOMY_ERROR;
        });
    }

    // ── Counter-Offer ────────────────────────────────────────────────────

    public CompletableFuture<Boolean> counterOffer(int contractId, UUID from, long newAmount, int newHours) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isNegotiating()) return done(false);

        boolean isHitman = from.equals(contract.getHitmanUuid());
        boolean isClient = from.equals(contract.getClientUuid());
        if (!isHitman && !isClient) return done(false);
        if (isHitman && !contract.getStatus().awaitingHitman()) return done(false);
        if (isClient && !contract.getStatus().awaitingClient()) return done(false);

        CompletableFuture<Boolean> balanceCheck;
        if (isHitman && newAmount > contract.getCurrentAmount()) {
            balanceCheck = economyManager.getBalanceCents(contract.getClientUuid())
                    .thenApply(balance -> balance >= newAmount);
        } else {
            balanceCheck = done(true);
        }

        return balanceCheck.thenCompose(canAfford -> {
            if (!canAfford) return done(false);

            ContractStatus newStatus = isHitman ? ContractStatus.COUNTER_FROM_HITMAN : ContractStatus.COUNTER_FROM_CLIENT;
            contract.setCounterOffer(newAmount, newHours, isHitman ? "HITMAN" : "CLIENT");
            contract.setStatus(newStatus);

            return databaseManager.updateContract(contract).thenApply(ok -> {
                plugin.debug("Counter-offer on contract " + contractId + " by " + from
                        + ": " + newAmount + " / " + newHours + "h");
                return ok;
            });
        });
    }

    // ── Accept Contract ──────────────────────────────────────────────────

    public CompletableFuture<AcceptResult> acceptContract(int contractId, UUID acceptedBy) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isNegotiating()) return done(AcceptResult.INVALID_STATE);

        boolean isHitman = acceptedBy.equals(contract.getHitmanUuid());
        boolean isClient = acceptedBy.equals(contract.getClientUuid());
        if (!isHitman && !isClient) return done(AcceptResult.INVALID_STATE);
        if (isHitman && !contract.getStatus().awaitingHitman()) return done(AcceptResult.INVALID_STATE);
        if (isClient && !contract.getStatus().awaitingClient()) return done(AcceptResult.INVALID_STATE);

        long finalAmount = contract.getCurrentAmount();
        int finalHours = contract.getCurrentHours();

        return economyManager.withdraw(contract.getClientUuid(), finalAmount, "Hitman contract accepted")
                .thenCompose(success -> {
                    if (!success) return done(AcceptResult.ECONOMY_ERROR);

                    long now = System.currentTimeMillis();
                    contract.setStatus(ContractStatus.ACCEPTED);
                    contract.setAcceptedAt(now);
                    contract.setExpiresAt(now + (long) finalHours * 3_600_000L);

                    return databaseManager.updateContract(contract).thenApply(ok -> {
                        if (!ok) {
                            economyManager.deposit(contract.getClientUuid(), finalAmount,
                                    "Hitman contract refund (DB error)");
                            contract.setStatus(ContractStatus.PENDING);
                            return AcceptResult.ECONOMY_ERROR;
                        }
                        plugin.debug("Contract " + contractId + " ACCEPTED. " + finalAmount
                                + " withdrawn, expires in " + finalHours + "h");

                        // Notify parties (accept messages + target BossBar) and send the
                        // anonymous warning to the target — all on the main thread.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getNotificationManager().notifyHitmanContractAccepted(contract);
                            plugin.getNotificationManager().broadcastAnonymousContract(
                                    contract.getTargetUuid());
                        });

                        return AcceptResult.SUCCESS;
                    });
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("Error accepting contract " + contractId + ": " + ex.getMessage());
                    return AcceptResult.ECONOMY_ERROR;
                });
    }

    // ── Decline / Cancel (pre-accept) ────────────────────────────────────

    public CompletableFuture<Boolean> declineContract(int contractId, UUID declinedBy) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isNegotiating()) return done(false);

        boolean isHitman = declinedBy.equals(contract.getHitmanUuid());
        boolean isClient = declinedBy.equals(contract.getClientUuid());
        if (!isHitman && !isClient) return done(false);

        ContractStatus newStatus = isHitman ? ContractStatus.DECLINED : ContractStatus.CANCELLED_BY_CLIENT;
        contract.setStatus(newStatus);
        contract.setCompletedAt(System.currentTimeMillis());
        removeFromCache(contract);
        return databaseManager.updateContract(contract);
    }

    // ── Complete Contract (hitman killed target) ──────────────────────────

    public CompletableFuture<Boolean> completeContract(HitmanContract contract) {
        // Atomic CAS: only the winner of the ACTIVE→terminal transition pays out.
        if (!contract.tryTerminate(ContractStatus.COMPLETED)) return done(false);

        long amount = contract.getCurrentAmount();
        removeFromCache(contract);

        return databaseManager.updateContract(contract)
                .thenCompose(ok -> economyManager.deposit(contract.getHitmanUuid(), amount,
                        "Hitman contract completed — killed " + contract.getTargetUuid()))
                .thenApply(deposited -> {
                    if (!deposited) {
                        plugin.getLogger().warning("Failed to pay hitman " + contract.getHitmanUuid()
                                + " prize of " + amount + " for contract " + contract.getId());
                    }
                    return true;
                });
    }

    // ── Cancel: someone else killed target (100% refund client) ──────────

    public CompletableFuture<Boolean> cancelTargetDied(HitmanContract contract) {
        // Atomic CAS: only the winner of the ACTIVE→terminal transition refunds.
        if (!contract.tryTerminate(ContractStatus.CANCELLED_TARGET_DIED)) return done(false);

        long amount = contract.getCurrentAmount();
        removeFromCache(contract);

        return databaseManager.updateContract(contract)
                .thenCompose(ok -> economyManager.deposit(contract.getClientUuid(), amount,
                        "Hitman target died — full refund"))
                .thenApply(deposited -> {
                    if (!deposited) {
                        plugin.getLogger().warning("Failed to refund client " + contract.getClientUuid()
                                + " for contract " + contract.getId());
                    }
                    return true;
                });
    }

    // ── Target killed the hitman (85% client / 15% target) ───────────────

    public CompletableFuture<Boolean> hitmanKilledByTarget(HitmanContract contract) {
        // Atomic CAS: only the winner of the ACTIVE→terminal transition pays out.
        if (!contract.tryTerminate(ContractStatus.FAILED_HITMAN_KILLED)) return done(false);

        long amount = contract.getCurrentAmount();
        int clientPct = configManager.getHitmanKilledClientPercent();
        long clientRefund = amount * clientPct / 100;
        long targetReward = amount - clientRefund;

        removeFromCache(contract);

        CompletableFuture<Boolean> dbFuture = databaseManager.updateContract(contract);
        CompletableFuture<Boolean> clientFuture = economyManager.deposit(contract.getClientUuid(), clientRefund,
                "Hitman killed — partial refund (" + clientPct + "%)");
        CompletableFuture<Boolean> targetFuture = economyManager.deposit(contract.getTargetUuid(), targetReward,
                "Killed a hitman hunting you");

        return CompletableFuture.allOf(dbFuture, clientFuture, targetFuture).thenApply(v -> true);
    }

    // ── Expire Contract (timer ran out → 100% refund client) ─────────────

    public CompletableFuture<Boolean> expireContract(HitmanContract contract) {
        // Atomic CAS: if the target was killed at the same instant the timer fired,
        // only one of the two transitions wins and refunds — never both.
        if (!contract.tryTerminate(ContractStatus.FAILED_TIMEOUT)) return done(false);

        long amount = contract.getCurrentAmount();
        removeFromCache(contract);

        return databaseManager.updateContract(contract)
                .thenCompose(ok -> economyManager.deposit(contract.getClientUuid(), amount,
                        "Hitman contract expired — full refund"))
                .thenApply(deposited -> true);
    }

    // ── Rescission (active contract cancellation negotiation) ─────────────

    public CompletableFuture<Boolean> requestRescission(int contractId, UUID requestedBy, int clientPercent) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isActive()) return done(false);
        if (contract.isRescissionPending()) return done(false);

        boolean isClient = requestedBy.equals(contract.getClientUuid());
        boolean isHitman = requestedBy.equals(contract.getHitmanUuid());
        if (!isClient && !isHitman) return done(false);

        clientPercent = Math.max(0, Math.min(100, clientPercent));
        contract.setRescission(clientPercent, isClient ? "CLIENT" : "HITMAN");
        return databaseManager.updateContract(contract);
    }

    public CompletableFuture<Boolean> counterRescission(int contractId, UUID from, int clientPercent) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isActive() || !contract.isRescissionPending()) return done(false);

        boolean isClient = from.equals(contract.getClientUuid());
        boolean isHitman = from.equals(contract.getHitmanUuid());
        if (!isClient && !isHitman) return done(false);
        if (isClient && "CLIENT".equals(contract.getRescissionProposedBy())) return done(false);
        if (isHitman && "HITMAN".equals(contract.getRescissionProposedBy())) return done(false);

        clientPercent = Math.max(0, Math.min(100, clientPercent));
        contract.setRescission(clientPercent, isClient ? "CLIENT" : "HITMAN");
        return databaseManager.updateContract(contract);
    }

    public CompletableFuture<Boolean> acceptRescission(int contractId, UUID acceptedBy) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.getStatus().isActive() || !contract.isRescissionPending()) return done(false);

        boolean isClient = acceptedBy.equals(contract.getClientUuid());
        boolean isHitman = acceptedBy.equals(contract.getHitmanUuid());
        if (!isClient && !isHitman) return done(false);
        if (isClient && "CLIENT".equals(contract.getRescissionProposedBy())) return done(false);
        if (isHitman && "HITMAN".equals(contract.getRescissionProposedBy())) return done(false);

        long amount = contract.getCurrentAmount();
        int clientPct = contract.getRescissionClientPercent();
        long clientShare = amount * clientPct / 100;
        long hitmanShare = amount - clientShare;

        // Atomic CAS: guard against the contract being terminated (killed/expired)
        // between the checks above and the payout — only one terminal transition wins.
        if (!contract.tryTerminate(ContractStatus.CANCELLED_RESCISSION)) return done(false);
        removeFromCache(contract);

        CompletableFuture<Boolean> dbFuture = databaseManager.updateContract(contract);
        CompletableFuture<Boolean> clientFuture = economyManager.deposit(contract.getClientUuid(), clientShare,
                "Contract rescission — " + clientPct + "% refund");
        CompletableFuture<Boolean> hitmanFuture = economyManager.deposit(contract.getHitmanUuid(), hitmanShare,
                "Contract rescission fee — " + (100 - clientPct) + "%");

        return CompletableFuture.allOf(dbFuture, clientFuture, hitmanFuture).thenApply(v -> true);
    }

    public CompletableFuture<Boolean> declineRescission(int contractId, UUID declinedBy) {
        HitmanContract contract = cacheById.get(contractId);
        if (contract == null || !contract.isRescissionPending()) return done(false);

        // Defense in depth: only a participant may decline the rescission.
        boolean isClient = declinedBy.equals(contract.getClientUuid());
        boolean isHitman = declinedBy.equals(contract.getHitmanUuid());
        if (!isClient && !isHitman) return done(false);

        contract.clearRescission();
        return databaseManager.updateContract(contract);
    }

    // ── Expiry Checks ────────────────────────────────────────────────────

    void checkExpiredContracts() {
        for (HitmanContract contract : new ArrayList<>(cacheById.values())) {
            if (contract.isExpired()) {
                plugin.debug("Contract " + contract.getId() + " expired. Refunding client.");
                final HitmanContract expired = contract;
                expireContract(contract).thenAccept(success -> {
                    if (success) {
                        // Notify on main thread
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.getNotificationManager().notifyContractExpired(expired));
                    }
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("Error expiring contract " + contract.getId() + ": " + ex.getMessage());
                    return null;
                });
            }
        }
    }

    void checkStaleNegotiations() {
        long staleMs = configManager.getStaleNegotiationMs();
        for (HitmanContract contract : new ArrayList<>(cacheById.values())) {
            if (contract.isStale(staleMs)) {
                plugin.debug("Contract " + contract.getId() + " is stale. Auto-cancelling.");
                final HitmanContract stale = contract;
                contract.setStatus(ContractStatus.CANCELLED_BY_CLIENT);
                contract.setCompletedAt(System.currentTimeMillis());
                removeFromCache(contract);
                databaseManager.updateContract(contract).thenAccept(ok -> {
                    if (ok) {
                        // Notify on main thread
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.getNotificationManager().notifyStaleNegotiationCancelled(stale));
                    }
                });
            }
        }
    }

    // ── Process Target Death (called from DeathListener) ─────────────────

    public CompletableFuture<List<ContractResult>> processTargetDeath(UUID targetUuid,
                                                                      Map<UUID, Double> validKillers) {
        List<HitmanContract> active = getActiveContractsForTarget(targetUuid);
        if (active.isEmpty()) return done(Collections.emptyList());

        List<CompletableFuture<ContractResult>> futures = new ArrayList<>();
        for (HitmanContract contract : active) {
            boolean hitmanDealtDamage = validKillers.containsKey(contract.getHitmanUuid());
            if (hitmanDealtDamage) {
                futures.add(completeContract(contract).thenApply(ok ->
                        new ContractResult(contract, ContractOutcome.COMPLETED)));
            } else {
                futures.add(cancelTargetDied(contract).thenApply(ok ->
                        new ContractResult(contract, ContractOutcome.CANCELLED_REFUND)));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    // ── Query Methods (cache-based) ──────────────────────────────────────

    public boolean hasActiveContracts(UUID targetUuid) {
        List<Integer> ids = contractsByTarget.get(targetUuid);
        if (ids == null) return false;
        return ids.stream().anyMatch(id -> {
            HitmanContract c = cacheById.get(id);
            return c != null && c.getStatus().isActive();
        });
    }

    public List<HitmanContract> getActiveContractsForTarget(UUID target) {
        return getContractsForUuid(contractsByTarget, target).stream()
                .filter(c -> c.getStatus().isActive())
                .collect(Collectors.toList());
    }

    public List<HitmanContract> getActiveContractsForHitman(UUID hitman) {
        return getContractsForUuid(contractsByHitman, hitman).stream()
                .filter(c -> c.getStatus().isActive())
                .collect(Collectors.toList());
    }

    public List<HitmanContract> getPendingForHitman(UUID hitman) {
        return getContractsForUuid(contractsByHitman, hitman).stream()
                .filter(c -> c.getStatus().awaitingHitman())
                .collect(Collectors.toList());
    }

    public List<HitmanContract> getContractsForClient(UUID client) {
        return getContractsForUuid(contractsByClient, client);
    }

    public List<HitmanContract> getAllContractsForHitman(UUID hitman) {
        return getContractsForUuid(contractsByHitman, hitman);
    }

    public int getActiveContractCount(UUID hitman) {
        return (int) getContractsForUuid(contractsByHitman, hitman).stream()
                .filter(c -> c.getStatus().isActive())
                .count();
    }

    public HitmanContract getContractById(int id) {
        return cacheById.get(id);
    }

    /** Get the active contract where the given player is the hitman for the given target. */
    public HitmanContract getActiveContractForHitmanOnTarget(UUID hitman, UUID target) {
        return getContractsForUuid(contractsByHitman, hitman).stream()
                .filter(c -> c.getStatus().isActive() && c.getTargetUuid().equals(target))
                .findFirst().orElse(null);
    }

    // ── Settings ─────────────────────────────────────────────────────────

    public HitmanSettings getSettings(UUID player) {
        HitmanSettings settings = settingsCache.get(player);
        if (settings == null) {
            settings = HitmanSettings.createDefault(player,
                    configManager.getDefaultHitmanMinPrice(),
                    configManager.getDefaultHitmanMinHours(),
                    configManager.getDefaultHitmanMaxHours(),
                    configManager.getDefaultHitmanMaxConcurrent());
        }
        return settings;
    }

    public CompletableFuture<Void> updateSettings(HitmanSettings settings) {
        settingsCache.put(settings.getUuid(), settings);
        return databaseManager.saveHitmanSettings(settings).thenAccept(ok -> {
            if (!ok) plugin.getLogger().warning("Failed to save hitman settings for " + settings.getUuid());
        });
    }

    public boolean isAvailable(UUID player) {
        HitmanSettings s = settingsCache.get(player);
        return s != null && s.isAvailable();
    }

    public List<HitmanSettings> getAvailableHitmen() {
        return settingsCache.values().stream()
                .filter(HitmanSettings::isAvailable)
                .collect(Collectors.toList());
    }

    // ── Cache Helpers ────────────────────────────────────────────────────

    private void addToCache(HitmanContract contract) {
        cacheById.put(contract.getId(), contract);
        addToIndex(contractsByTarget, contract.getTargetUuid(), contract.getId());
        addToIndex(contractsByHitman, contract.getHitmanUuid(), contract.getId());
        addToIndex(contractsByClient, contract.getClientUuid(), contract.getId());
    }

    private void removeFromCache(HitmanContract contract) {
        cacheById.remove(contract.getId());
        removeFromIndex(contractsByTarget, contract.getTargetUuid(), contract.getId());
        removeFromIndex(contractsByHitman, contract.getHitmanUuid(), contract.getId());
        removeFromIndex(contractsByClient, contract.getClientUuid(), contract.getId());
    }

    private void addToIndex(Map<UUID, List<Integer>> index, UUID key, int contractId) {
        index.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(contractId);
    }

    private void removeFromIndex(Map<UUID, List<Integer>> index, UUID key, int contractId) {
        List<Integer> ids = index.get(key);
        if (ids != null) {
            ids.remove(Integer.valueOf(contractId));
            if (ids.isEmpty()) index.remove(key);
        }
    }

    private List<HitmanContract> getContractsForUuid(Map<UUID, List<Integer>> index, UUID key) {
        List<Integer> ids = index.get(key);
        if (ids == null) return Collections.emptyList();
        return ids.stream()
                .map(cacheById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    // ── Result Types ─────────────────────────────────────────────────────

    public enum CreateResult {
        SUCCESS, SELF_TARGET, SELF_HIRE, HITMAN_IS_TARGET, SAME_FACTION,
        BELOW_MIN_PRICE, INVALID_HOURS, HITMAN_UNAVAILABLE, HITMAN_MAX_CONTRACTS,
        NOT_ENOUGH_MONEY, ECONOMY_ERROR
    }

    public enum AcceptResult {
        SUCCESS, CANNOT_AFFORD, ECONOMY_ERROR, INVALID_STATE
    }

    public enum ContractOutcome {
        COMPLETED, CANCELLED_REFUND
    }

    public record ContractResult(HitmanContract contract, ContractOutcome outcome) {}
}

