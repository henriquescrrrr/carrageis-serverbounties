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
 * Shows bounties the player has contributed to.
 * Each entry shows target + amount. Click to cancel contribution.
 */
public final class MyBountiesGui {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    private MyBountiesGui() {}

    public static void open(Player viewer, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        BountyManager bountyMgr = plugin.getBountyManager();

        Map<UUID, List<BountyEntry>> contributed = bountyMgr.getContributedBounties(viewer.getUniqueId());

        List<BountyEntry> allEntries = new ArrayList<>();
        for (List<BountyEntry> entries : contributed.values()) {
            allEntries.addAll(entries);
        }

        if (allEntries.isEmpty()) {
            viewer.sendMessage(lang.get(viewer, "bounty-none-active"));
            return;
        }

        allEntries.sort(Comparator.comparingLong(BountyEntry::getCreatedAt).reversed());

        int size = gc.getSize("my-bounties");
        int itemsPerPage = gc.getItemsPerPage("my-bounties");
        int totalPages = Math.max(1, (int) Math.ceil((double) allEntries.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-my-bounties-title");
        GuiHolder holder = new GuiHolder("my-bounties", size, title);
        holder.setData("page", page);

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, allEntries.size());

        for (int i = start; i < end; i++) {
            BountyEntry entry = allEntries.get(i);
            String targetName = getPlayerName(entry.getTargetUuid());
            String formattedAmount = econ.formatCurrency(entry.getAmountCents());
            String date = DATE_FORMAT.format(new Date(entry.getCreatedAt()));
            long totalOnTarget = bountyMgr.getTotalBounty(entry.getTargetUuid());

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getTargetUuid()));
            skullMeta.displayName(Component.text("§e" + targetName));
            skullMeta.lore(List.of(
                    Component.text("§7Your contribution: §f" + formattedAmount),
                    Component.text("§7Total on target: §f" + econ.formatCurrency(totalOnTarget)),
                    Component.text("§7Date: §f" + date),
                    Component.text(""),
                    lang.get(viewer, "gui-cancel-contribution"),
                    lang.get(viewer, "gui-cancel-contribution-lore", "amount", formattedAmount)
            ));
            head.setItemMeta(skullMeta);

            final int entryId = entry.getId();
            final UUID entryTarget = entry.getTargetUuid();
            final long entryAmount = entry.getAmountCents();
            holder.setItem(i - start, head, p -> {
                // Cancel bounty contribution
                bountyMgr.cancelContribution(p.getUniqueId(), entryTarget, entryId)
                        .thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (success) {
                                p.sendMessage(lang.get(p, "bounty-cancelled",
                                        "amount", econ.formatCurrency(entryAmount)));
                            } else {
                                p.sendMessage(lang.get(p, "bounty-cancel-failed"));
                            }
                            // Refresh the GUI
                            open(p, 0);
                        }));
            });
        }

        // Navigation
        final int currentPage = page;
        final int tp = totalPages;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("my-bounties", "prev-page"), prev,
                    p -> open(p, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("my-bounties", "next-page"), next,
                    p -> open(p, currentPage + 1));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("my-bounties", "back"), back,
                p -> MainMenuGui.open(p));

        int pageInfoSlot = gc.getSlot("my-bounties", "page-info");
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
