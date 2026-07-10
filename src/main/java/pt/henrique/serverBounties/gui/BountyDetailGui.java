package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.manager.BountyEntry;
import pt.henrique.serverbounties.manager.BountyManager;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Shows all contributors for a specific bounty target.
 * Each entry: contributor head + amount + date.
 */
public final class BountyDetailGui {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    private BountyDetailGui() {}

    public static void open(Player viewer, UUID targetUuid, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        BountyManager bountyMgr = plugin.getBountyManager();

        List<BountyEntry> entries = bountyMgr.getEntries(targetUuid);
        if (entries.isEmpty()) {
            viewer.sendMessage(lang.get(viewer, "bounty-no-bounty",
                    "target", getPlayerName(targetUuid)));
            return;
        }

        entries.sort(Comparator.comparingLong(BountyEntry::getAmountCents).reversed());

        int size = gc.getSize("bounty-detail");
        int itemsPerPage = gc.getItemsPerPage("bounty-detail");
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String targetName = getPlayerName(targetUuid);
        long total = bountyMgr.getTotalBounty(targetUuid);

        Component title = lang.get(viewer, "gui-bounty-detail-title", "target", targetName);
        GuiHolder holder = new GuiHolder("bounty-detail", size, title);
        holder.setData("page", page);
        holder.setData("targetUuid", targetUuid);

        // Fill contributor items
        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, entries.size());

        for (int i = start; i < end; i++) {
            BountyEntry entry = entries.get(i);
            String contributorName = getPlayerName(entry.getContributorUuid());
            String formattedAmount = econ.formatCurrency(entry.getAmountCents());
            String date = DATE_FORMAT.format(new Date(entry.getCreatedAt()));

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getContributorUuid()));
            skullMeta.displayName(Component.text("§e" + contributorName));
            skullMeta.lore(List.of(
                    Component.text("§7Amount: §f" + formattedAmount),
                    Component.text("§7Date: §f" + date),
                    Component.text("§7Entry ID: §8#" + entry.getId())
            ));
            head.setItemMeta(skullMeta);
            holder.setItem(i - start, head);
        }

        // Navigation
        final int currentPage = page;
        final int tp = totalPages;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("bounty-detail", "prev-page"), prev,
                    p -> open(p, targetUuid, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("bounty-detail", "next-page"), next,
                    p -> open(p, targetUuid, currentPage + 1));
        }

        // Back to board
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("bounty-detail", "back"), back,
                p -> BountyBoardGui.open(p, 0));

        // Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(lang.get(viewer, "gui-close"));
        close.setItemMeta(closeMeta);
        holder.setItem(gc.getSlot("bounty-detail", "close"), close, p -> p.closeInventory());

        // Total bounty info
        int totalInfoSlot = gc.getSlot("bounty-detail", "total-info");
        if (totalInfoSlot >= 0) {
            ItemStack info = new ItemStack(Material.GOLD_INGOT);
            ItemMeta infoMeta = info.getItemMeta();
            infoMeta.displayName(Component.text("§6Total Bounty: §f" + econ.formatCurrency(total)));
            info.setItemMeta(infoMeta);
            holder.setItem(totalInfoSlot, info);
        }

        // Page indicator
        int pageInfoSlot = gc.getSlot("bounty-detail", "page-info");
        if (pageInfoSlot >= 0) {
            ItemStack pageInfo = new ItemStack(Material.PAPER);
            ItemMeta pageMeta = pageInfo.getItemMeta();
            pageMeta.displayName(Component.text("§7Page " + (currentPage + 1) + "/" + tp));
            pageInfo.setItemMeta(pageMeta);
            holder.setItem(pageInfoSlot, pageInfo);
        }

        holder.fill();
        holder.open(viewer);
    }

    private static String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}

