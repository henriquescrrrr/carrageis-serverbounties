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
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.model.HitmanSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paginated GUI showing all online hitmen who are available.
 */
public final class HitmanBoardGui {

    private HitmanBoardGui() {}

    public static void open(Player viewer, int page) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        List<Player> availableHitmen = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer)) continue;
            if (!hitmanMgr.isAvailable(online.getUniqueId())) continue;
            availableHitmen.add(online);
        }
        availableHitmen.sort(Comparator.comparing(Player::getName));

        int size = gc.getSize("hitman-board");
        int itemsPerPage = gc.getItemsPerPage("hitman-board");
        int totalPages = Math.max(1, (int) Math.ceil((double) availableHitmen.size() / itemsPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Component title = lang.get(viewer, "gui-hitman-board-title");
        GuiHolder holder = new GuiHolder("hitman-board", size, title);
        holder.setData("page", page);

        if (availableHitmen.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta emptyMeta = empty.getItemMeta();
            emptyMeta.displayName(lang.get(viewer, "gui-hitman-board-empty"));
            empty.setItemMeta(emptyMeta);
            holder.setItem(22, empty);
        } else {
            int start = page * itemsPerPage;
            int end = Math.min(start + itemsPerPage, availableHitmen.size());

            for (int i = start; i < end; i++) {
                Player hitman = availableHitmen.get(i);
                HitmanSettings settings = hitmanMgr.getSettings(hitman.getUniqueId());
                int activeCount = hitmanMgr.getActiveContractCount(hitman.getUniqueId());

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(hitman);
                meta.displayName(Component.text("§e" + hitman.getName()));
                meta.lore(List.of(
                        lang.get(viewer, "gui-hitman-board-lore-price",
                                "amount", econ.formatCurrency(settings.getMinPriceCents())),
                        lang.get(viewer, "gui-hitman-board-lore-hours",
                                "min", String.valueOf(settings.getMinHours()),
                                "max", String.valueOf(settings.getMaxHours())),
                        lang.get(viewer, "gui-hitman-board-lore-contracts",
                                "active", String.valueOf(activeCount),
                                "max", String.valueOf(settings.getMaxConcurrent())),
                        Component.text(""),
                        lang.get(viewer, "gui-hitman-board-lore-click")
                ));
                head.setItemMeta(meta);
                // Click → hire this hitman (same flow as /hitman hire <name>):
                // opens target selection with the hitman pre-selected.
                final java.util.UUID hitmanUuid = hitman.getUniqueId();
                holder.setItem(i - start, head,
                        p -> HitmanGui.openSelectTarget(p, 0, hitmanUuid));
            }
        }

        // Navigation
        final int currentPage = page;

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.displayName(lang.get(viewer, "gui-prev-page"));
            prev.setItemMeta(prevMeta);
            holder.setItem(gc.getSlot("hitman-board", "prev-page"), prev,
                    p -> open(p, currentPage - 1));
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.displayName(lang.get(viewer, "gui-next-page"));
            next.setItemMeta(nextMeta);
            holder.setItem(gc.getSlot("hitman-board", "next-page"), next,
                    p -> open(p, currentPage + 1));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-board", "back"), back,
                p -> MainMenuGui.open(p));

        holder.fill();
        holder.open(viewer);
    }
}
