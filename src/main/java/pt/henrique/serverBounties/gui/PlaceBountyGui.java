package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.ConfigManager;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.hook.FactionsHook;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.notification.NotificationManager;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Place bounty GUI flow:
 * Phase 1: Select target (online players, filters self/faction)
 * Phase 2: Adjust amount (±100, ±1000, ±10000, shift = ×10) + confirm
 */
public final class PlaceBountyGui {

    private PlaceBountyGui() {}

    // ── Phase 1: Select Player ───────────────────────────────────────────

    public static void openSelectPlayer(Player viewer, int page) {
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

        int size = gc.getSize("place-bounty-select");
        int itemsPerPage = gc.getItemsPerPage("place-bounty-select");
        int totalPages = Math.max(1, (int) Math.ceil((double) validTargets.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-select-player-title");
        GuiHolder holder = new GuiHolder("place-bounty-select", size, title);
        holder.setData("page", page);

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, validTargets.size());

        for (int i = start; i < end; i++) {
            Player target = validTargets.get(i);
            BountyManager bountyMgr = plugin.getBountyManager();
            long existingBounty = bountyMgr.getTotalBounty(target.getUniqueId());

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(target);
            skullMeta.displayName(Component.text("§e" + target.getName()));
            List<Component> lore = new ArrayList<>();
            if (existingBounty > 0) {
                lore.add(Component.text("§7Current bounty: §f" + plugin.getEconomyManager().formatCurrency(existingBounty)));
            }
            lore.add(Component.text("§eClick to place bounty"));
            skullMeta.lore(lore);
            head.setItemMeta(skullMeta);

            final UUID targetUuid = target.getUniqueId();
            holder.setItem(i - start, head,
                    p -> openAmountSelector(p, targetUuid, config.getMinAmount()));
        }

        // Navigation
        final int currentPage = page;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("place-bounty-select", "prev-page"), prev,
                    p -> openSelectPlayer(p, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("place-bounty-select", "next-page"), next,
                    p -> openSelectPlayer(p, currentPage + 1));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("place-bounty-select", "back"), back,
                p -> MainMenuGui.open(p));

        holder.fill();
        holder.open(viewer);
    }

    // ── Phase 2: Adjust Amount ───────────────────────────────────────────

    public static void openAmountSelector(Player viewer, UUID targetUuid, long currentAmount) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        ConfigManager config = plugin.getConfigManager();

        if (currentAmount < config.getMinAmount()) currentAmount = config.getMinAmount();
        if (currentAmount > config.getMaxAmountPerContribution()) currentAmount = config.getMaxAmountPerContribution();

        String targetName = getPlayerName(targetUuid);
        int size = gc.getSize("place-bounty-amount");
        Component title = lang.get(viewer, "gui-place-bounty-title");
        GuiHolder holder = new GuiHolder("place-bounty-amount", size, title);
        holder.setData("targetUuid", targetUuid);
        holder.setData("amount", currentAmount);

        // Target head
        ItemStack targetHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) targetHead.getItemMeta();
        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
        skullMeta.displayName(Component.text("§eTarget: §f" + targetName));
        long existingBounty = plugin.getBountyManager().getTotalBounty(targetUuid);
        if (existingBounty > 0) {
            skullMeta.lore(List.of(Component.text("§7Current bounty: §f" + econ.formatCurrency(existingBounty))));
        }
        targetHead.setItemMeta(skullMeta);
        holder.setItem(gc.getSlot("place-bounty-amount", "target-head"), targetHead);

        // Amount display
        long tax = Math.round(currentAmount * config.getTaxRate());
        long totalCost = currentAmount + tax;
        String taxPercent = String.valueOf(Math.round(config.getTaxRate() * 100));

        ItemStack amountDisplay = new ItemStack(Material.SUNFLOWER);
        ItemMeta amountMeta = amountDisplay.getItemMeta();
        amountMeta.displayName(lang.get(viewer, "gui-amount-display", "amount", econ.formatCurrency(currentAmount)));
        amountMeta.lore(List.of(
                lang.get(viewer, "gui-tax-info",
                        "percent", taxPercent,
                        "tax", econ.formatCurrency(tax),
                        "cost", econ.formatCurrency(totalCost))
        ));
        amountDisplay.setItemMeta(amountMeta);
        holder.setItem(gc.getSlot("place-bounty-amount", "amount-display"), amountDisplay);

        // Amount adjustment buttons with shift support
        final long amt = currentAmount;
        BiConsumer<Player, ClickType> makeAdjuster = (delta, shiftDelta) -> {};
        // Decrease buttons
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "dec-100"),
                Material.RED_STAINED_GLASS_PANE, "§c-100", "§7Shift: -1,000",
                -100, -1000, targetUuid, amt, config);
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "dec-1k"),
                Material.RED_STAINED_GLASS_PANE, "§c-1,000", "§7Shift: -10,000",
                -1000, -10000, targetUuid, amt, config);
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "dec-10k"),
                Material.RED_STAINED_GLASS_PANE, "§c-10,000", "§7Shift: -100,000",
                -10000, -100000, targetUuid, amt, config);

        // Increase buttons
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "inc-100"),
                Material.LIME_STAINED_GLASS_PANE, "§a+100", "§7Shift: +1,000",
                100, 1000, targetUuid, amt, config);
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "inc-1k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+1,000", "§7Shift: +10,000",
                1000, 10000, targetUuid, amt, config);
        setAdjustButton(holder, gc.getSlot("place-bounty-amount", "inc-10k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+10,000", "§7Shift: +100,000",
                10000, 100000, targetUuid, amt, config);

        // Confirm button
        final long confirmAmount = currentAmount;
        ItemStack confirm = new ItemStack(Material.LIME_WOOL);
        ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.displayName(lang.get(viewer, "gui-confirm-bounty", "amount", econ.formatCurrency(currentAmount)));
        String loreRaw = lang.getRaw(viewer, "gui-confirm-bounty-lore");
        // Escape the player-influenced target name so MiniMessage tags in it aren't parsed.
        loreRaw = loreRaw.replace("{target}", lang.escapeValue(targetName))
                .replace("{amount}", econ.formatCurrency(currentAmount))
                .replace("{tax_percent}", taxPercent)
                .replace("{tax}", econ.formatCurrency(tax))
                .replace("{cost}", econ.formatCurrency(totalCost));
        List<Component> loreLines = new ArrayList<>();
        for (String line : loreRaw.split("\\\\n|\\n")) {
            loreLines.add(lang.parse(line));
        }
        confirmMeta.lore(loreLines);
        confirm.setItemMeta(confirmMeta);
        holder.setItem(gc.getSlot("place-bounty-amount", "confirm"), confirm,
                p -> confirmBounty(p, targetUuid, confirmAmount));

        // Cancel button
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(lang.get(viewer, "gui-cancel"));
        cancel.setItemMeta(cancelMeta);
        holder.setItem(gc.getSlot("place-bounty-amount", "cancel"), cancel, p -> p.closeInventory());

        // Back button
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("place-bounty-amount", "back"), back,
                p -> openSelectPlayer(p, 0));

        holder.fill();
        holder.open(viewer);
    }

    private static void setAdjustButton(GuiHolder holder, int slot, Material mat,
                                         String name, String shiftInfo,
                                         long delta, long shiftDelta,
                                         UUID targetUuid, long currentAmount,
                                         ConfigManager config) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text(shiftInfo)));
        item.setItemMeta(meta);
        holder.setItem(slot, item, (p, click) -> {
            long d = click.isShiftClick() ? shiftDelta : delta;
            long newAmount = currentAmount + d;
            newAmount = Math.max(config.getMinAmount(), Math.min(newAmount, config.getMaxAmountPerContribution()));
            openAmountSelector(p, targetUuid, newAmount);
        });
    }

    // ── Confirm Logic (called by click handler) ──────────────────────────

    public static void confirmBounty(Player viewer, UUID targetUuid, long amountCents) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        ConfigManager config = plugin.getConfigManager();
        NotificationManager notif = plugin.getNotificationManager();

        viewer.closeInventory();

        if (!config.isAllowOfflineTargets() && Bukkit.getPlayer(targetUuid) == null) {
            viewer.sendMessage(lang.get(viewer, "bounty-offline"));
            return;
        }

        plugin.getBountyManager().placeBounty(viewer.getUniqueId(), targetUuid, amountCents)
                .thenAccept(result -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        switch (result) {
                            case SUCCESS -> {
                                long tax = Math.round(amountCents * config.getTaxRate());
                                long totalCost = amountCents + tax;
                                long newTotal = plugin.getBountyManager().getTotalBounty(targetUuid);
                                notif.notifyBountyPlaced(viewer.getUniqueId(), targetUuid,
                                        amountCents, totalCost, newTotal);
                            }
                            case SELF -> viewer.sendMessage(lang.get(viewer, "bounty-self"));
                            case SAME_FACTION -> viewer.sendMessage(lang.get(viewer, "bounty-same-faction"));
                            case BELOW_MIN -> viewer.sendMessage(lang.get(viewer, "bounty-min-amount",
                                    "min", plugin.getEconomyManager().formatCurrency(config.getMinAmount())));
                            case ABOVE_MAX -> viewer.sendMessage(lang.get(viewer, "bounty-max-amount",
                                    "max", plugin.getEconomyManager().formatCurrency(config.getMaxAmountPerContribution())));
                            case CAP_REACHED -> viewer.sendMessage(lang.get(viewer, "bounty-max-total",
                                    "target", getPlayerName(targetUuid),
                                    "max", plugin.getEconomyManager().formatCurrency(config.getMaxTotalPerTarget())));
                            case NOT_ENOUGH_MONEY -> {
                                plugin.getEconomyManager().getBalanceCents(viewer.getUniqueId())
                                        .thenAccept(balance -> Bukkit.getScheduler().runTask(plugin, () -> {
                                            long tax = Math.round(amountCents * config.getTaxRate());
                                            viewer.sendMessage(lang.get(viewer, "bounty-not-enough-money",
                                                    "cost", plugin.getEconomyManager().formatCurrency(amountCents + tax),
                                                    "balance", plugin.getEconomyManager().formatCurrency(balance)));
                                        }));
                            }
                            case ECONOMY_ERROR -> viewer.sendMessage(lang.get(viewer, "bounty-economy-error"));
                            case TARGET_OFFLINE -> viewer.sendMessage(lang.get(viewer, "bounty-offline"));
                        }
                    });
                });
    }

    private static String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}

