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
import pt.henrique.serverbounties.model.HitmanContract;

import java.util.List;
import java.util.UUID;

/**
 * GUI for sending a counter-offer on an existing hitman contract.
 * Amount and hours adjustment with send/cancel.
 */
public final class CounterOfferGui {

    private CounterOfferGui() {}

    public static void open(Player viewer, int contractId) {
        ServerBounties plugin = ServerBounties.getInstance();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        HitmanContract contract = hitmanMgr.getContractById(contractId);
        if (contract == null || !contract.getStatus().isNegotiating()) {
            viewer.sendMessage(plugin.getLangManager().get(viewer, "hitman-no-contracts"));
            return;
        }

        openWithValues(viewer, contractId, contract.getCurrentAmount(), contract.getCurrentHours());
    }

    public static void openWithValues(Player viewer, int contractId, long amount, int hours) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        HitmanContract contract = hitmanMgr.getContractById(contractId);
        if (contract == null) return;

        String targetName = getPlayerName(contract.getTargetUuid());

        int size = gc.getSize("counter-offer");
        Component title = lang.get(viewer, "gui-counter-title");
        GuiHolder holder = new GuiHolder("counter-offer", size, title);
        holder.setData("contractId", contractId);
        holder.setData("amount", amount);
        holder.setData("hours", hours);

        // Target head
        ItemStack targetHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta targetMeta = (SkullMeta) targetHead.getItemMeta();
        targetMeta.setOwningPlayer(Bukkit.getOfflinePlayer(contract.getTargetUuid()));
        targetMeta.displayName(lang.get(viewer, "gui-negotiation-target", "target", targetName));
        targetHead.setItemMeta(targetMeta);
        holder.setItem(gc.getSlot("counter-offer", "target-head"), targetHead);

        // Amount adjustment
        setAmountButton(holder, gc.getSlot("counter-offer", "dec-10k"),
                Material.RED_STAINED_GLASS_PANE, "§c-10,000", "§7Shift: -100,000",
                -10000, -100000, contractId, amount, hours);
        setAmountButton(holder, gc.getSlot("counter-offer", "dec-1k"),
                Material.RED_STAINED_GLASS_PANE, "§c-1,000", "§7Shift: -10,000",
                -1000, -10000, contractId, amount, hours);
        setAmountButton(holder, gc.getSlot("counter-offer", "dec-100"),
                Material.RED_STAINED_GLASS_PANE, "§c-100", "§7Shift: -1,000",
                -100, -1000, contractId, amount, hours);

        // Amount display
        ItemStack amountDisplay = new ItemStack(Material.GOLD_INGOT);
        ItemMeta amountMeta = amountDisplay.getItemMeta();
        amountMeta.displayName(lang.get(viewer, "gui-counter-amount", "amount", econ.formatCurrency(amount)));
        amountDisplay.setItemMeta(amountMeta);
        holder.setItem(gc.getSlot("counter-offer", "amount-display"), amountDisplay);

        setAmountButton(holder, gc.getSlot("counter-offer", "inc-100"),
                Material.LIME_STAINED_GLASS_PANE, "§a+100", "§7Shift: +1,000",
                100, 1000, contractId, amount, hours);
        setAmountButton(holder, gc.getSlot("counter-offer", "inc-1k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+1,000", "§7Shift: +10,000",
                1000, 10000, contractId, amount, hours);
        setAmountButton(holder, gc.getSlot("counter-offer", "inc-10k"),
                Material.LIME_STAINED_GLASS_PANE, "§a+10,000", "§7Shift: +100,000",
                10000, 100000, contractId, amount, hours);

        // Hours adjustment
        setHoursButton(holder, gc.getSlot("counter-offer", "dec-24h"),
                Material.RED_STAINED_GLASS_PANE, "§c-24h", null,
                -24, contractId, amount, hours);
        setHoursButton(holder, gc.getSlot("counter-offer", "dec-1h"),
                Material.RED_STAINED_GLASS_PANE, "§c-1h", null,
                -1, contractId, amount, hours);

        // Hours display
        ItemStack hoursDisplay = new ItemStack(Material.CLOCK);
        ItemMeta hoursMeta = hoursDisplay.getItemMeta();
        hoursMeta.displayName(lang.get(viewer, "gui-counter-hours", "hours", String.valueOf(hours)));
        hoursDisplay.setItemMeta(hoursMeta);
        holder.setItem(gc.getSlot("counter-offer", "hours-display"), hoursDisplay);

        setHoursButton(holder, gc.getSlot("counter-offer", "inc-1h"),
                Material.LIME_STAINED_GLASS_PANE, "§a+1h", null,
                1, contractId, amount, hours);
        setHoursButton(holder, gc.getSlot("counter-offer", "inc-24h"),
                Material.LIME_STAINED_GLASS_PANE, "§a+24h", null,
                24, contractId, amount, hours);

        // Send
        ItemStack send = new ItemStack(Material.GREEN_WOOL);
        ItemMeta sendMeta = send.getItemMeta();
        sendMeta.displayName(lang.get(viewer, "gui-counter-send"));
        send.setItemMeta(sendMeta);
        holder.setItem(gc.getSlot("counter-offer", "send"), send, p -> {
            p.closeInventory();
            hitmanMgr.counterOffer(contractId, p.getUniqueId(), amount, hours);
            p.sendMessage(lang.get(p, "hitman-counter-sent"));
        });

        // Cancel
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(lang.get(viewer, "gui-cancel"));
        cancel.setItemMeta(cancelMeta);
        holder.setItem(gc.getSlot("counter-offer", "cancel"), cancel,
                p -> NegotiationGui.open(p, contractId));

        holder.fill();
        holder.open(viewer);
    }

    private static void setAmountButton(GuiHolder holder, int slot, Material mat,
                                         String name, String lore,
                                         long delta, long shiftDelta,
                                         int contractId, long currentAmount, int currentHours) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore != null) meta.lore(List.of(Component.text(lore)));
        item.setItemMeta(meta);
        holder.setItem(slot, item, (p, click) -> {
            long d = click.isShiftClick() ? shiftDelta : delta;
            long newAmount = Math.max(100, currentAmount + d);
            openWithValues(p, contractId, newAmount, currentHours);
        });
    }

    private static void setHoursButton(GuiHolder holder, int slot, Material mat,
                                        String name, String lore,
                                        int delta, int contractId,
                                        long currentAmount, int currentHours) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore != null) meta.lore(List.of(Component.text(lore)));
        item.setItemMeta(meta);
        holder.setItem(slot, item, p -> {
            int newHours = Math.max(1, currentHours + delta);
            openWithValues(p, contractId, currentAmount, newHours);
        });
    }

    private static String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}

