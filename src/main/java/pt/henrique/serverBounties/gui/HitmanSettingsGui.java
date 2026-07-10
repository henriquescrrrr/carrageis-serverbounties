package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.model.HitmanSettings;

import java.util.List;

/**
 * GUI for a player to configure their hitman profile settings.
 * 3 rows (27 slots). Each click modifies in-memory state and re-opens.
 */
public final class HitmanSettingsGui {

    private HitmanSettingsGui() {}

    /**
     * Open with current DB settings (initial open).
     */
    public static void open(Player viewer) {
        ServerBounties plugin = ServerBounties.getInstance();
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        HitmanSettings settings = hitmanMgr.getSettings(viewer.getUniqueId());

        openWithValues(viewer, settings.isAvailable(), settings.getMinPriceCents(),
                settings.getMinHours(), settings.getMaxHours(), settings.getMaxConcurrent());
    }

    /**
     * Open/refresh with specific in-memory values.
     */
    public static void openWithValues(Player viewer, boolean available, long minPrice,
                                       int minHours, int maxHours, int maxConcurrent) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();

        int size = gc.getSize("hitman-settings");
        Component title = lang.get(viewer, "gui-hitman-settings-title");
        GuiHolder holder = new GuiHolder("hitman-settings", size, title);

        // Store current values in holder data
        holder.setData("available", available);
        holder.setData("minPrice", minPrice);
        holder.setData("minHours", minHours);
        holder.setData("maxHours", maxHours);
        holder.setData("maxConcurrent", maxConcurrent);

        // Toggle Available
        Material toggleMat = available ? Material.GREEN_WOOL : Material.RED_WOOL;
        String toggleKey = available ? "gui-toggle-available-on" : "gui-toggle-available-off";
        ItemStack toggle = new ItemStack(toggleMat);
        ItemMeta toggleMeta = toggle.getItemMeta();
        toggleMeta.displayName(lang.get(viewer, toggleKey));
        toggleMeta.lore(List.of(lang.get(viewer, "gui-hitman-settings-toggle-lore")));
        toggle.setItemMeta(toggleMeta);
        holder.setItem(gc.getSlot("hitman-settings", "toggle-available"), toggle,
                p -> openWithValues(p, !available, minPrice, minHours, maxHours, maxConcurrent));

        // Min Price — left/right click to adjust
        ItemStack minPriceItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta mpMeta = minPriceItem.getItemMeta();
        mpMeta.displayName(lang.get(viewer, "gui-min-price", "amount", econ.formatCurrency(minPrice)));
        mpMeta.lore(List.of(lang.get(viewer, "gui-hitman-settings-min-price-lore")));
        minPriceItem.setItemMeta(mpMeta);
        holder.setItem(gc.getSlot("hitman-settings", "min-price"), minPriceItem, (p, click) -> {
            long delta = click.isShiftClick() ? 10000 : 1000;
            if (click.isRightClick()) delta = -delta;
            long newPrice = Math.max(0, minPrice + delta);
            openWithValues(p, available, newPrice, minHours, maxHours, maxConcurrent);
        });

        // Min Hours
        ItemStack minHoursItem = new ItemStack(Material.CLOCK);
        ItemMeta mhMeta = minHoursItem.getItemMeta();
        mhMeta.displayName(lang.get(viewer, "gui-min-hours", "hours", String.valueOf(minHours)));
        mhMeta.lore(List.of(lang.get(viewer, "gui-hitman-settings-min-hours-lore")));
        minHoursItem.setItemMeta(mhMeta);
        holder.setItem(gc.getSlot("hitman-settings", "min-hours"), minHoursItem, (p, click) -> {
            int delta = click.isShiftClick() ? 24 : 1;
            if (click.isRightClick()) delta = -delta;
            int newMinH = Math.max(1, Math.min(minHours + delta, maxHours));
            openWithValues(p, available, minPrice, newMinH, maxHours, maxConcurrent);
        });

        // Max Hours
        ItemStack maxHoursItem = new ItemStack(Material.CLOCK);
        ItemMeta mxhMeta = maxHoursItem.getItemMeta();
        mxhMeta.displayName(lang.get(viewer, "gui-max-hours", "hours", String.valueOf(maxHours)));
        mxhMeta.lore(List.of(lang.get(viewer, "gui-hitman-settings-max-hours-lore")));
        maxHoursItem.setItemMeta(mxhMeta);
        holder.setItem(gc.getSlot("hitman-settings", "max-hours"), maxHoursItem, (p, click) -> {
            int delta = click.isShiftClick() ? 24 : 1;
            if (click.isRightClick()) delta = -delta;
            int newMaxH = Math.max(minHours, maxHours + delta);
            openWithValues(p, available, minPrice, minHours, newMaxH, maxConcurrent);
        });

        // Max Concurrent
        ItemStack maxConcItem = new ItemStack(Material.NAME_TAG);
        ItemMeta mcMeta = maxConcItem.getItemMeta();
        mcMeta.displayName(lang.get(viewer, "gui-max-concurrent", "count", String.valueOf(maxConcurrent)));
        mcMeta.lore(List.of(lang.get(viewer, "gui-hitman-settings-max-concurrent-lore")));
        maxConcItem.setItemMeta(mcMeta);
        holder.setItem(gc.getSlot("hitman-settings", "max-concurrent"), maxConcItem, (p, click) -> {
            int delta = click.isRightClick() ? -1 : 1;
            int newMaxC = Math.max(1, maxConcurrent + delta);
            openWithValues(p, available, minPrice, minHours, maxHours, newMaxC);
        });

        // Save
        ItemStack save = new ItemStack(Material.LIME_WOOL);
        ItemMeta saveMeta = save.getItemMeta();
        saveMeta.displayName(lang.get(viewer, "gui-hitman-settings-save"));
        save.setItemMeta(saveMeta);
        holder.setItem(gc.getSlot("hitman-settings", "save"), save, p -> {
            HitmanManager hitmanMgr = plugin.getHitmanManager();
            HitmanSettings settings = hitmanMgr.getSettings(p.getUniqueId());
            settings.setAvailable(available);
            settings.setMinPriceCents(minPrice);
            settings.setMinHours(minHours);
            settings.setMaxHours(maxHours);
            settings.setMaxConcurrent(maxConcurrent);
            hitmanMgr.updateSettings(settings);
            p.sendMessage(lang.get(p, "hitman-settings-saved"));
            p.closeInventory();
        });

        // Back
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("hitman-settings", "back"), back,
                p -> MainMenuGui.open(p));

        // Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(lang.get(viewer, "gui-close"));
        close.setItemMeta(closeMeta);
        holder.setItem(gc.getSlot("hitman-settings", "close"), close, p -> p.closeInventory());

        holder.fill();
        holder.open(viewer);
    }
}
