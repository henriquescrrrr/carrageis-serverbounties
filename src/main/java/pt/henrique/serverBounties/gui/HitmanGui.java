package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.hook.FactionsHook;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.model.HitmanSettings;
import pt.henrique.serverbounties.manager.HitmanManager;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hitman GUI flows: select target, select hitman, adjust amount,
 * my contracts list, active contract detail.
 */
public final class HitmanGui {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    private HitmanGui() {}

    // ── Phase 1: Select Target ───────────────────────────────────────────

    /**
     * Open target selection without a pre-selected hitman.
     */
    public static void openSelectTarget(Player viewer, int page) {
        openSelectTarget(viewer, page, null);
    }

    /**
     * Open target selection. If {@code preSelectedHitman} is non-null the hitman
     * selection step is skipped and the viewer goes straight to amount selection.
     */
    public static void openSelectTarget(Player viewer, int page, UUID preSelectedHitman) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        ConfigManager config = plugin.getConfigManager();
        FactionsHook factions = plugin.getFactionsHook();

        List<Player> validTargets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer)) continue;
            if (!config.isAllowSameFaction() && factions.areInSameFaction(viewer.getUniqueId(), online.getUniqueId())) continue;
            validTargets.add(online);
        }
        validTargets.sort(Comparator.comparing(Player::getName));

        int size = gc.getSize("hitman-select-target");
        int itemsPerPage = gc.getItemsPerPage("hitman-select-target");
        int totalPages = Math.max(1, (int) Math.ceil((double) validTargets.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-hitman-select-target-title");
        GuiHolder holder = new GuiHolder("hitman-select-target", size, title);
        holder.setData("page", page);
        if (preSelectedHitman != null) {
            holder.setData("preSelectedHitman", preSelectedHitman);
        }

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, validTargets.size());

        for (int i = start; i < end; i++) {
            Player target = validTargets.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.displayName(Component.text("§e" + target.getName()));
            meta.lore(List.of(Component.text("§eClick to select as target")));
            head.setItemMeta(meta);

            final UUID targetUuid = target.getUniqueId();
            if (preSelectedHitman != null) {
                HitmanManager hitmanMgr = plugin.getHitmanManager();
                HitmanSettings settings = hitmanMgr.getSettings(preSelectedHitman);
                final UUID psHitman = preSelectedHitman;
                holder.setItem(i - start, head,
                        p -> openAmountSelector(p, targetUuid, psHitman, settings.getMinPriceCents()));
            } else {
                holder.setItem(i - start, head,
                        p -> openSelectHitman(p, targetUuid, 0));
            }
        }

        // Navigation
        final int currentPage = page;
        final UUID psHitmanNav = preSelectedHitman;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("hitman-select-target", "prev-page"), prev,
                    p -> openSelectTarget(p, currentPage - 1, psHitmanNav));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("hitman-select-target", "next-page"), next,
                    p -> openSelectTarget(p, currentPage + 1, psHitmanNav));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-select-target", "back"), back,
                p -> MainMenuGui.open(p));

        holder.fill();
        holder.open(viewer);
    }

    // ── Phase 2: Select Hitman ───────────────────────────────────────────

    public static void openSelectHitman(Player viewer, UUID targetUuid, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        List<Player> validHitmen = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer)) continue;
            if (online.getUniqueId().equals(targetUuid)) continue;
            if (!hitmanMgr.isAvailable(online.getUniqueId())) continue;
            validHitmen.add(online);
        }
        validHitmen.sort(Comparator.comparing(Player::getName));

        int size = gc.getSize("hitman-select-hitman");
        int itemsPerPage = gc.getItemsPerPage("hitman-select-hitman");
        int totalPages = Math.max(1, (int) Math.ceil((double) validHitmen.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-hitman-select-hitman-title");
        GuiHolder holder = new GuiHolder("hitman-select-hitman", size, title);
        holder.setData("page", page);
        holder.setData("targetUuid", targetUuid);

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, validHitmen.size());

        for (int i = start; i < end; i++) {
            Player hitman = validHitmen.get(i);
            HitmanSettings settings = hitmanMgr.getSettings(hitman.getUniqueId());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(hitman);
            meta.displayName(Component.text("§e" + hitman.getName()));
            meta.lore(List.of(
                    Component.text("§7Status: §aAvailable"),
                    Component.text("§7Min price: §f" + plugin.getEconomyManager().formatCurrency(settings.getMinPriceCents())),
                    Component.text("§7Hours: §f" + settings.getMinHours() + "h - " + settings.getMaxHours() + "h"),
                    Component.text("§eClick to hire")
            ));
            head.setItemMeta(meta);

            final UUID hitmanUuid = hitman.getUniqueId();
            holder.setItem(i - start, head,
                    p -> openAmountSelector(p, targetUuid, hitmanUuid, settings.getMinPriceCents()));
        }

        // Navigation
        final int currentPage = page;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("hitman-select-hitman", "prev-page"), prev,
                    p -> openSelectHitman(p, targetUuid, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("hitman-select-hitman", "next-page"), next,
                    p -> openSelectHitman(p, targetUuid, currentPage + 1));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-select-hitman", "back"), back,
                p -> openSelectTarget(p, 0));

        holder.fill();
        holder.open(viewer);
    }

    // ── Phase 3: Adjust Amount + Hours ────────────────────────────────────

    public static void openAmountSelector(Player viewer, UUID targetUuid, UUID hitmanUuid, long currentAmount) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        HitmanSettings settings = hitmanMgr.getSettings(hitmanUuid);
        if (currentAmount < settings.getMinPriceCents()) currentAmount = settings.getMinPriceCents();

        String targetName = getPlayerName(targetUuid);
        String hitmanName = getPlayerName(hitmanUuid);

        int size = gc.getSize("hitman-amount");
        Component title = lang.get(viewer, "gui-hitman-place-title");
        GuiHolder holder = new GuiHolder("hitman-amount", size, title);
        holder.setData("targetUuid", targetUuid);
        holder.setData("hitmanUuid", hitmanUuid);
        holder.setData("amount", currentAmount);

        // Target head
        ItemStack targetHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta targetMeta = (SkullMeta) targetHead.getItemMeta();
        targetMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
        targetMeta.displayName(Component.text("§cTarget: §f" + targetName));
        targetHead.setItemMeta(targetMeta);
        holder.setItem(gc.getSlot("hitman-amount", "target-head"), targetHead);

        // Hitman head
        ItemStack hitmanHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta hitmanMeta = (SkullMeta) hitmanHead.getItemMeta();
        hitmanMeta.setOwningPlayer(Bukkit.getOfflinePlayer(hitmanUuid));
        hitmanMeta.displayName(Component.text("§5Hitman: §f" + hitmanName));
        hitmanHead.setItemMeta(hitmanMeta);
        holder.setItem(gc.getSlot("hitman-amount", "hitman-head"), hitmanHead);

        // Amount display
        ItemStack prizeDisplay = new ItemStack(Material.DIAMOND);
        ItemMeta prizeMeta = prizeDisplay.getItemMeta();
        prizeMeta.displayName(lang.get(viewer, "gui-amount-display", "amount", econ.formatCurrency(currentAmount)));
        prizeDisplay.setItemMeta(prizeMeta);
        holder.setItem(gc.getSlot("hitman-amount", "amount-display"), prizeDisplay);

        // Amount adjustment buttons
        final long amt = currentAmount;
        final long minPrice = settings.getMinPriceCents();

        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "dec-100"),
                Material.RED_STAINED_GLASS_PANE, "§c-100", "§7Shift: -1,000",
                -100, -1000, targetUuid, hitmanUuid, amt, minPrice);
        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "dec-1k"),
                Material.RED_STAINED_GLASS_PANE, "§c-1,000", "§7Shift: -10,000",
                -1000, -10000, targetUuid, hitmanUuid, amt, minPrice);
        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "dec-10k"),
                Material.RED_STAINED_GLASS_PANE, "§c-10,000", "§7Shift: -100,000",
                -10000, -100000, targetUuid, hitmanUuid, amt, minPrice);

        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "inc-100"),
                Material.LIME_STAINED_GLASS_PANE, "§a+100", "§7Shift: +1,000",
                100, 1000, targetUuid, hitmanUuid, amt, minPrice);
        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "inc-1k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+1,000", "§7Shift: +10,000",
                1000, 10000, targetUuid, hitmanUuid, amt, minPrice);
        setHitmanAdjustButton(holder, gc.getSlot("hitman-amount", "inc-10k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+10,000", "§7Shift: +100,000",
                10000, 100000, targetUuid, hitmanUuid, amt, minPrice);

        // Confirm
        final long confirmAmount = currentAmount;
        ItemStack confirm = new ItemStack(Material.LIME_WOOL);
        ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.displayName(Component.text("§aConfirm: Send proposal for " + econ.formatCurrency(currentAmount)));
        confirm.setItemMeta(confirmMeta);
        holder.setItem(gc.getSlot("hitman-amount", "confirm"), confirm,
                p -> confirmContract(p, targetUuid, hitmanUuid, confirmAmount));

        // Cancel
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(lang.get(viewer, "gui-cancel"));
        cancel.setItemMeta(cancelMeta);
        holder.setItem(gc.getSlot("hitman-amount", "cancel"), cancel, p -> p.closeInventory());

        // Back
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-amount", "back"), back,
                p -> openSelectHitman(p, targetUuid, 0));

        holder.fill();
        holder.open(viewer);
    }

    private static void setHitmanAdjustButton(GuiHolder holder, int slot, Material mat,
                                               String name, String shiftInfo,
                                               long delta, long shiftDelta,
                                               UUID targetUuid, UUID hitmanUuid,
                                               long currentAmount, long minPrice) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text(shiftInfo)));
        item.setItemMeta(meta);
        holder.setItem(slot, item, (p, click) -> {
            long d = click.isShiftClick() ? shiftDelta : delta;
            long newAmount = Math.max(minPrice, currentAmount + d);
            openAmountSelector(p, targetUuid, hitmanUuid, newAmount);
        });
    }

    // ── My Contracts ─────────────────────────────────────────────────────

    public static void openMyContracts(Player viewer, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        UUID viewerUuid = viewer.getUniqueId();
        List<HitmanContract> contracts = new ArrayList<>(hitmanMgr.getContractsForClient(viewerUuid));
        for (HitmanContract c : hitmanMgr.getAllContractsForHitman(viewerUuid)) {
            if (!contracts.contains(c)) contracts.add(c);
        }
        if (contracts.isEmpty()) {
            viewer.sendMessage(lang.get(viewer, "hitman-no-contracts"));
            return;
        }

        contracts.sort(Comparator.comparingLong(HitmanContract::getCreatedAt).reversed());

        int size = gc.getSize("hitman-my-contracts");
        int itemsPerPage = gc.getItemsPerPage("hitman-my-contracts");
        int totalPages = Math.max(1, (int) Math.ceil((double) contracts.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-hitman-my-contracts-title");
        GuiHolder holder = new GuiHolder("hitman-my-contracts", size, title);
        holder.setData("page", page);

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, contracts.size());

        for (int i = start; i < end; i++) {
            HitmanContract c = contracts.get(i);
            String targetName = getPlayerName(c.getTargetUuid());
            String hitmanName = getPlayerName(c.getHitmanUuid());
            String amount = econ.formatCurrency(c.getCurrentAmount());
            String date = DATE_FORMAT.format(new Date(c.getCreatedAt()));

            boolean isViewerClient = viewerUuid.equals(c.getClientUuid());
            String role = isViewerClient ? "Client" : "Hitman";

            Material mat = switch (c.getStatus()) {
                case PENDING, COUNTER_FROM_HITMAN, COUNTER_FROM_CLIENT -> Material.YELLOW_WOOL;
                case ACCEPTED -> Material.GREEN_WOOL;
                case COMPLETED -> Material.GOLD_BLOCK;
                default -> Material.RED_WOOL;
            };

            String statusKey = switch (c.getStatus()) {
                case PENDING, COUNTER_FROM_HITMAN, COUNTER_FROM_CLIENT -> "gui-contract-status-pending";
                case ACCEPTED -> "gui-contract-status-active";
                case COMPLETED -> "gui-contract-status-completed";
                default -> "gui-contract-status-failed";
            };

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Target: §f" + targetName));
            lore.add(Component.text("§7Hitman: §f" + hitmanName));
            lore.add(Component.text("§7Amount: §f" + amount));
            lore.add(lang.get(viewer, statusKey));
            lore.add(Component.text("§7Role: §f" + role));
            lore.add(Component.text("§7Date: §f" + date));

            if (c.getStatus().isActive()) {
                long remainingMs = c.getRemainingMs();
                String remaining = remainingMs > 0
                        ? String.format("%dh %dm", remainingMs / 3_600_000, (remainingMs % 3_600_000) / 60_000)
                        : "N/A";
                lore.add(lang.get(viewer, "gui-time-remaining", "time", remaining));
                if (c.isRescissionPending()) {
                    lore.add(lang.get(viewer, "gui-contract-respond-rescission"));
                }
            }
            lore.add(Component.text(""));
            lore.add(Component.text("§eClick to view"));

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("§cTarget: §f" + targetName));
            meta.lore(lore);
            item.setItemMeta(meta);

            final int contractId = c.getId();
            final boolean isNegotiating = c.getStatus().isNegotiating();
            final boolean isActive = c.getStatus().isActive();
            holder.setItem(i - start, item, p -> {
                if (isNegotiating) {
                    NegotiationGui.open(p, contractId);
                } else if (isActive) {
                    openActiveContract(p, contractId);
                }
            });
        }

        // Navigation
        final int currentPage = page;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("hitman-my-contracts", "prev-page"), prev,
                    p -> openMyContracts(p, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("hitman-my-contracts", "next-page"), next,
                    p -> openMyContracts(p, currentPage + 1));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-my-contracts", "back"), back,
                p -> MainMenuGui.open(p));

        holder.fill();
        holder.open(viewer);
    }

    // ── Active Contract (detail view) ───────────────────────────────────

    public static void openActiveContract(Player viewer) {
        openActiveContract(viewer, -1);
    }

    /**
     * Open the active contract detail view. If {@code contractId >= 0} that specific
     * contract is shown (the viewer must be a participant); otherwise the viewer's
     * first active contract is used.
     */
    public static void openActiveContract(Player viewer, int contractId) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        UUID viewerUuid = viewer.getUniqueId();

        HitmanContract contract = null;
        if (contractId >= 0) {
            HitmanContract byId = hitmanMgr.getContractById(contractId);
            if (byId != null && byId.getStatus().isActive()
                    && (viewerUuid.equals(byId.getClientUuid()) || viewerUuid.equals(byId.getHitmanUuid()))) {
                contract = byId;
            }
        }
        if (contract == null) {
            List<HitmanContract> active = hitmanMgr.getActiveContractsForHitman(viewerUuid);
            if (active.isEmpty()) {
                active = hitmanMgr.getContractsForClient(viewerUuid).stream()
                        .filter(c -> c.getStatus().isActive())
                        .collect(Collectors.toList());
            }
            if (active.isEmpty()) {
                viewer.sendMessage(lang.get(viewer, "hitman-no-contracts"));
                return;
            }
            contract = active.get(0);
        }

        boolean isHitman = viewerUuid.equals(contract.getHitmanUuid());
        boolean isClient = viewerUuid.equals(contract.getClientUuid());

        int size = gc.getSize("hitman-active-contract");
        Component title = lang.get(viewer, "gui-hitman-active-contract-title");
        GuiHolder holder = new GuiHolder("hitman-active-contract", size, title);
        holder.setData("contractId", contract.getId());

        // Target head
        String targetName = getPlayerName(contract.getTargetUuid());
        String amount = econ.formatCurrency(contract.getCurrentAmount());
        String date = DATE_FORMAT.format(new Date(contract.getCreatedAt()));
        long remainingMs = contract.getRemainingMs();
        String remaining = remainingMs > 0
                ? String.format("%dh %dm", remainingMs / 3_600_000, (remainingMs % 3_600_000) / 60_000)
                : "N/A";

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(contract.getTargetUuid()));
        meta.displayName(Component.text("§c⚔ Target: §f" + targetName));
        List<Component> headLore = new ArrayList<>();
        headLore.add(Component.text("§7Prize: §f" + amount));
        headLore.add(lang.get(viewer, "gui-time-remaining", "time", remaining));
        headLore.add(Component.text("§7Date: §f" + date));
        headLore.add(Component.text(""));
        if (isHitman) {
            headLore.add(Component.text("§7Client: §8Anonymous"));
            headLore.add(Component.text(""));
            headLore.add(Component.text("§eKill this player to complete the contract!"));
        } else {
            headLore.add(Component.text("§7Hitman: §f" + getPlayerName(contract.getHitmanUuid())));
        }
        meta.lore(headLore);
        head.setItemMeta(meta);
        holder.setItem(gc.getSlot("hitman-active-contract", "target-head"), head);

        // Client: "Request Cancellation" button
        final int cId = contract.getId();
        if (isClient) {
            int clientActionSlot = gc.getSlot("hitman-active-contract", "client-action");
            if (contract.isRescissionPending()) {
                ItemStack respond = new ItemStack(Material.ORANGE_WOOL);
                ItemMeta respondMeta = respond.getItemMeta();
                respondMeta.displayName(lang.get(viewer, "gui-contract-respond-rescission"));
                respond.setItemMeta(respondMeta);
                holder.setItem(clientActionSlot, respond,
                        p -> RescissionGui.open(p, cId));
            } else {
                ItemStack cancelReq = new ItemStack(Material.ORANGE_WOOL);
                ItemMeta cancelReqMeta = cancelReq.getItemMeta();
                cancelReqMeta.displayName(lang.get(viewer, "gui-contract-request-cancel"));
                cancelReqMeta.lore(List.of(
                        lang.get(viewer, "gui-contract-request-cancel-lore")
                ));
                cancelReq.setItemMeta(cancelReqMeta);
                holder.setItem(clientActionSlot, cancelReq,
                        p -> RescissionGui.open(p, cId));
            }
        }

        // Hitman: respond to rescission if pending
        if (isHitman && contract.isRescissionPending()) {
            int hitmanActionSlot = gc.getSlot("hitman-active-contract", "hitman-action");
            ItemStack respond = new ItemStack(Material.ORANGE_WOOL);
            ItemMeta respondMeta = respond.getItemMeta();
            respondMeta.displayName(lang.get(viewer, "gui-contract-respond-rescission"));
            respond.setItemMeta(respondMeta);
            holder.setItem(hitmanActionSlot, respond,
                    p -> RescissionGui.open(p, cId));
        }

        // Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(lang.get(viewer, "gui-close"));
        close.setItemMeta(closeMeta);
        holder.setItem(gc.getSlot("hitman-active-contract", "close"), close, p -> p.closeInventory());

        holder.fill();
        holder.open(viewer);
    }

    // ── Confirm Logic (sends proposal, no money withdrawn) ───────────────

    public static void confirmContract(Player viewer, UUID targetUuid, UUID hitmanUuid, long amountCents) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        FactionsHook factions = plugin.getFactionsHook();

        HitmanSettings settings = hitmanMgr.getSettings(hitmanUuid);
        // Default proposal duration is 24h, clamped into the hitman's accepted
        // [minHours, maxHours] range — otherwise a hitman with maxHours < 24 could
        // never be hired (createProposal would reject every proposal as INVALID_HOURS).
        int hours = Math.min(Math.max(24, settings.getMinHours()), settings.getMaxHours());

        if (factions.isAvailable() && factions.areInSameFaction(hitmanUuid, targetUuid)) {
            FactionWarningGui.open(viewer, hitmanUuid, targetUuid, amountCents, hours);
            return;
        }

        sendProposal(viewer, targetUuid, hitmanUuid, amountCents, hours);
    }

    public static void sendProposal(Player viewer, UUID targetUuid, UUID hitmanUuid, long amountCents, int hours) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        HitmanSettings settings = hitmanMgr.getSettings(hitmanUuid);

        viewer.closeInventory();

        hitmanMgr.createProposal(viewer.getUniqueId(), hitmanUuid, targetUuid, amountCents, hours)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (result) {
                        case SUCCESS -> {
                            plugin.getNotificationManager().notifyHitmanProposalSent(
                                    viewer.getUniqueId(), hitmanUuid, targetUuid, amountCents, hours);
                        }
                        case SELF_TARGET -> viewer.sendMessage(lang.get(viewer, "hitman-self-target"));
                        case SELF_HIRE -> viewer.sendMessage(lang.get(viewer, "hitman-self-hire"));
                        case HITMAN_IS_TARGET -> viewer.sendMessage(lang.get(viewer, "hitman-hitman-is-target"));
                        case SAME_FACTION -> viewer.sendMessage(lang.get(viewer, "hitman-same-faction"));
                        case BELOW_MIN_PRICE -> viewer.sendMessage(lang.get(viewer, "hitman-below-min-price",
                                "hitman", getPlayerName(hitmanUuid),
                                "min", econ.formatCurrency(settings.getMinPriceCents())));
                        case INVALID_HOURS -> viewer.sendMessage(lang.get(viewer, "hitman-offline"));
                        case HITMAN_UNAVAILABLE -> viewer.sendMessage(lang.get(viewer, "hitman-offline"));
                        case HITMAN_MAX_CONTRACTS -> viewer.sendMessage(lang.get(viewer, "hitman-max-contracts",
                                "hitman", getPlayerName(hitmanUuid)));
                        case NOT_ENOUGH_MONEY -> econ.getBalanceCents(viewer.getUniqueId())
                                .thenAccept(balance -> Bukkit.getScheduler().runTask(plugin, () ->
                                        viewer.sendMessage(lang.get(viewer, "hitman-cant-afford",
                                                "amount", econ.formatCurrency(amountCents),
                                                "balance", econ.formatCurrency(balance)))));
                        case ECONOMY_ERROR -> viewer.sendMessage(lang.get(viewer, "hitman-economy-error"));
                    }
                }));
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}

