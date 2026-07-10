package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;

import java.util.List;
import java.util.UUID;

/**
 * Warning GUI shown when the client tries to hire a hitman who is in the
 * same faction as the target. 1 row (9 slots).
 */
public final class FactionWarningGui {

    private FactionWarningGui() {}

    public static void open(Player viewer, UUID hitmanUuid, UUID targetUuid, long amount, int hours) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();

        String hitmanName = getPlayerName(hitmanUuid);
        String targetName = getPlayerName(targetUuid);

        int size = gc.getSize("faction-warning");
        Component title = lang.get(viewer, "gui-faction-warning-title");
        GuiHolder holder = new GuiHolder("faction-warning", size, title);
        holder.setData("hitmanUuid", hitmanUuid);
        holder.setData("targetUuid", targetUuid);
        holder.setData("amount", amount);
        holder.setData("hours", hours);

        // Warning item
        ItemStack warning = new ItemStack(Material.BARRIER);
        ItemMeta warningMeta = warning.getItemMeta();
        warningMeta.displayName(lang.get(viewer, "gui-faction-warning-text"));
        warningMeta.lore(List.of(
                lang.get(viewer, "gui-faction-warning-lore-1",
                        "hitman", hitmanName, "target", targetName),
                lang.get(viewer, "gui-faction-warning-lore-2"),
                lang.get(viewer, "gui-faction-warning-lore-3"),
                Component.text(""),
                lang.get(viewer, "gui-faction-warning-lore-4")
        ));
        warning.setItemMeta(warningMeta);
        holder.setItem(gc.getSlot("faction-warning", "warning-item"), warning);

        // Continue
        ItemStack cont = new ItemStack(Material.YELLOW_WOOL);
        ItemMeta contMeta = cont.getItemMeta();
        contMeta.displayName(lang.get(viewer, "gui-faction-warning-continue"));
        cont.setItemMeta(contMeta);
        holder.setItem(gc.getSlot("faction-warning", "continue"), cont,
                p -> HitmanGui.sendProposal(p, targetUuid, hitmanUuid, amount, hours));

        // Cancel
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(lang.get(viewer, "gui-faction-warning-cancel"));
        cancel.setItemMeta(cancelMeta);
        holder.setItem(gc.getSlot("faction-warning", "cancel"), cancel, p -> p.closeInventory());

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
