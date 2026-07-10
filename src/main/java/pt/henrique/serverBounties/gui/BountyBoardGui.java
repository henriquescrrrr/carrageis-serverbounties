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
import pt.henrique.serverbounties.manager.BountyManager;

import java.util.*;

/**
 * Paginated bounty board GUI showing all active bounties sorted by total descending.
 * Each slot is a player head with bounty info. Click → opens BountyDetailGui.
 */
public final class BountyBoardGui {

    private BountyBoardGui() {}

    public static void open(Player viewer, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        BountyManager bountyMgr = plugin.getBountyManager();

        Map<UUID, Long> allBounties = bountyMgr.getAllBounties();
        if (allBounties.isEmpty()) {
            viewer.sendMessage(lang.get(viewer, "bounty-none-active"));
            return;
        }

        int size = gc.getSize("bounty-board");
        int itemsPerPage = gc.getItemsPerPage("bounty-board");
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(allBounties.entrySet());
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-board-title");
        GuiHolder holder = new GuiHolder("bounty-board", size, title);
        holder.setData("page", page);

        // Fill bounty items
        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, entries.size());

        for (int i = start; i < end; i++) {
            Map.Entry<UUID, Long> entry = entries.get(i);
            UUID targetUuid = entry.getKey();
            long total = entry.getValue();
            int contributors = bountyMgr.getContributorCount(targetUuid);

            String targetName = getPlayerName(targetUuid);
            String formatted = econ.formatCurrency(total);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
            skullMeta.displayName(Component.text("§e" + targetName));
            skullMeta.lore(List.of(
                    Component.text("§7Bounty: §f" + formatted),
                    Component.text("§7Contributors: §f" + contributors),
                    Component.text(""),
                    Component.text("§eClick to view details")
            ));
            head.setItemMeta(skullMeta);

            final UUID clickTarget = targetUuid;
            holder.setItem(i - start, head, p -> BountyDetailGui.open(p, clickTarget, 0));
        }

        // Navigation
        final int currentPage = page;
        final int tp = totalPages;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("bounty-board", "prev-page"), prev,
                    p -> open(p, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("bounty-board", "next-page"), next,
                    p -> open(p, currentPage + 1));
        }

        // Back to menu
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("bounty-board", "back"), back,
                p -> MainMenuGui.open(p));

        // Page indicator
        int pageInfoSlot = gc.getSlot("bounty-board", "page-info");
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
