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
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.model.HitmanContract;

import java.util.List;
import java.util.UUID;

/**
 * GUI for negotiating active contract cancellation (rescission).
 * Client and hitman negotiate a percentage split of the escrowed money.
 */
public final class RescissionGui {

    private RescissionGui() {}

    public static void open(Player viewer, int contractId) {
        ServerBounties plugin = ServerBounties.getInstance();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        HitmanContract contract = hitmanMgr.getContractById(contractId);
        if (contract == null || !contract.getStatus().isActive()) {
            viewer.sendMessage(plugin.getLangManager().get(viewer, "hitman-no-contracts"));
            return;
        }

        int startPercent = contract.isRescissionPending()
                ? contract.getRescissionClientPercent()
                : 50;

        openWithPercent(viewer, contractId, startPercent);
    }

    public static void openWithPercent(Player viewer, int contractId, int clientPercent) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        HitmanContract contract = hitmanMgr.getContractById(contractId);
        if (contract == null) return;

        clientPercent = Math.max(0, Math.min(100, clientPercent));
        int hitmanPercent = 100 - clientPercent;

        long totalAmount = contract.getCurrentAmount();
        long clientAmount = totalAmount * clientPercent / 100;
        long hitmanAmount = totalAmount - clientAmount;

        UUID viewerUuid = viewer.getUniqueId();
        boolean isClient = viewerUuid.equals(contract.getClientUuid());
        boolean isHitman = viewerUuid.equals(contract.getHitmanUuid());
        boolean isRespondingToOther = contract.isRescissionPending() &&
                ((isClient && "HITMAN".equals(contract.getRescissionProposedBy())) ||
                 (isHitman && "CLIENT".equals(contract.getRescissionProposedBy())));

        String clientName = getPlayerName(contract.getClientUuid());
        String hitmanName = getPlayerName(contract.getHitmanUuid());
        String targetName = getPlayerName(contract.getTargetUuid());

        int size = gc.getSize("rescission");
        Component title = lang.get(viewer, "gui-rescission-title");
        GuiHolder holder = new GuiHolder("rescission", size, title);
        holder.setData("contractId", contractId);
        holder.setData("clientPercent", clientPercent);

        // Contract info
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(lang.get(viewer, "gui-rescission-info",
                "id", String.valueOf(contract.getId()),
                "target", targetName));
        infoMeta.lore(List.of(
                lang.get(viewer, "gui-rescission-amount",
                        "amount", econ.formatCurrency(totalAmount))
        ));
        info.setItemMeta(infoMeta);
        holder.setItem(gc.getSlot("rescission", "contract-info"), info);

        // Client share
        ItemStack clientShare = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta clientMeta = clientShare.getItemMeta();
        clientMeta.displayName(lang.get(viewer, "gui-rescission-client-share",
                "percent", String.valueOf(clientPercent),
                "amount", econ.formatCurrency(clientAmount)));
        clientShare.setItemMeta(clientMeta);
        holder.setItem(gc.getSlot("rescission", "client-share"), clientShare);

        // Slider display
        ItemStack slider = new ItemStack(Material.ARROW);
        ItemMeta sliderMeta = slider.getItemMeta();
        sliderMeta.displayName(lang.get(viewer, "gui-rescission-split",
                "client_percent", String.valueOf(clientPercent),
                "hitman_percent", String.valueOf(hitmanPercent)));
        slider.setItemMeta(sliderMeta);
        holder.setItem(gc.getSlot("rescission", "slider-display"), slider);

        // Hitman share
        ItemStack hitmanShare = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta hitmanMeta = hitmanShare.getItemMeta();
        hitmanMeta.displayName(lang.get(viewer, "gui-rescission-hitman-share",
                "percent", String.valueOf(hitmanPercent),
                "amount", econ.formatCurrency(hitmanAmount)));
        hitmanShare.setItemMeta(hitmanMeta);
        holder.setItem(gc.getSlot("rescission", "hitman-share"), hitmanShare);

        // Percentage adjustment buttons
        final int cp = clientPercent;
        holder.setItem(gc.getSlot("rescission", "dec-10pct"),
                createButton(Material.RED_STAINED_GLASS_PANE, "§c-10%"),
                p -> openWithPercent(p, contractId, cp - 10));
        holder.setItem(gc.getSlot("rescission", "dec-5pct"),
                createButton(Material.RED_STAINED_GLASS_PANE, "§c-5%"),
                p -> openWithPercent(p, contractId, cp - 5));

        // Percentage bar
        ItemStack bar = new ItemStack(Material.GOLD_INGOT);
        ItemMeta barMeta = bar.getItemMeta();
        barMeta.displayName(lang.get(viewer, "gui-rescission-split",
                "client_percent", String.valueOf(clientPercent),
                "hitman_percent", String.valueOf(hitmanPercent)));
        bar.setItemMeta(barMeta);
        holder.setItem(gc.getSlot("rescission", "percentage-bar"), bar);

        holder.setItem(gc.getSlot("rescission", "inc-5pct"),
                createButton(Material.LIME_STAINED_GLASS_PANE, "§a+5%"),
                p -> openWithPercent(p, contractId, cp + 5));
        holder.setItem(gc.getSlot("rescission", "inc-10pct"),
                createButton(Material.LIME_STAINED_GLASS_PANE, "§a+10%"),
                p -> openWithPercent(p, contractId, cp + 10));

        // Back
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("rescission", "back"), back,
                p -> HitmanGui.openActiveContract(p, contractId));

        // Propose / Accept / Counter.
        // When responding to the other party's proposal: accepting with the slider on
        // the PROPOSED split accepts it; moving the slider first sends a COUNTER-proposal
        // (never a blind accept of a split different from what the button shows).
        final int finalClientPercent = clientPercent;
        final int proposedPercent = contract.getRescissionClientPercent();
        final boolean responding = isRespondingToOther;
        final boolean isCounter = responding && finalClientPercent != proposedPercent;

        // While the viewer's OWN proposal is pending, there is nothing to propose or
        // accept — requestRescission would reject a duplicate. Only Decline (withdraw
        // the proposal) and Close are shown in that state.
        boolean canAct = !contract.isRescissionPending() || responding;
        if (canAct) {
            ItemStack action = new ItemStack(Material.GREEN_WOOL);
            ItemMeta actionMeta = action.getItemMeta();
            String actionKey = (responding && !isCounter) ? "gui-rescission-accept" : "gui-rescission-propose";
            actionMeta.displayName(lang.get(viewer, actionKey,
                    "client_percent", String.valueOf(clientPercent),
                    "hitman_percent", String.valueOf(hitmanPercent)));
            action.setItemMeta(actionMeta);
            holder.setItem(gc.getSlot("rescission", "propose-accept"), action, p -> {
                p.closeInventory();
                if (responding && !isCounter) {
                    // Accept the proposed split exactly as displayed.
                    hitmanMgr.acceptRescission(contractId, p.getUniqueId())
                            .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (ok) {
                                    // Contract left the cache on success — compute the accepter's share.
                                    long cShare = totalAmount * proposedPercent / 100;
                                    long myShare = p.getUniqueId().equals(contract.getClientUuid())
                                            ? cShare : totalAmount - cShare;
                                    p.sendMessage(lang.get(p, "hitman-rescission-accepted",
                                            "amount", econ.formatCurrency(myShare)));
                                } else {
                                    p.sendMessage(lang.get(p, "hitman-no-contracts"));
                                }
                            }));
                } else if (isCounter) {
                    // Slider moved while responding → counter-proposal, not an accept.
                    hitmanMgr.counterRescission(contractId, p.getUniqueId(), finalClientPercent)
                            .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (ok) {
                                    p.sendMessage(lang.get(p, "gui-rescission-counter-sent",
                                            "client_percent", String.valueOf(finalClientPercent),
                                            "hitman_percent", String.valueOf(100 - finalClientPercent)));
                                    HitmanContract updated = hitmanMgr.getContractById(contractId);
                                    if (updated != null) {
                                        plugin.getNotificationManager().notifyRescissionProposed(updated);
                                    }
                                } else {
                                    p.sendMessage(lang.get(p, "hitman-no-contracts"));
                                }
                            }));
                } else {
                    hitmanMgr.requestRescission(contractId, p.getUniqueId(), finalClientPercent)
                            .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (ok) {
                                    p.sendMessage(lang.get(p, "gui-rescission-proposed-sent",
                                            "client_percent", String.valueOf(finalClientPercent),
                                            "hitman_percent", String.valueOf(100 - finalClientPercent)));
                                    HitmanContract updated = hitmanMgr.getContractById(contractId);
                                    if (updated != null) {
                                        plugin.getNotificationManager().notifyRescissionProposed(updated);
                                    }
                                } else {
                                    p.sendMessage(lang.get(p, "hitman-no-contracts"));
                                }
                            }));
                }
            });
        }

        // Decline (only meaningful while a rescission is pending)
        ItemStack decline = new ItemStack(Material.RED_WOOL);
        ItemMeta declineMeta = decline.getItemMeta();
        declineMeta.displayName(lang.get(viewer, "gui-rescission-decline"));
        decline.setItemMeta(declineMeta);
        holder.setItem(gc.getSlot("rescission", "decline"), decline, p -> {
            p.closeInventory();
            hitmanMgr.declineRescission(contractId, p.getUniqueId())
                    .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ok) {
                            p.sendMessage(lang.get(p, "hitman-rescission-declined"));
                            HitmanContract updated = hitmanMgr.getContractById(contractId);
                            if (updated != null) {
                                plugin.getNotificationManager().notifyRescissionDeclined(updated, p.getUniqueId());
                            }
                        } else {
                            p.sendMessage(lang.get(p, "hitman-no-contracts"));
                        }
                    }));
        });

        // Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(lang.get(viewer, "gui-close"));
        close.setItemMeta(closeMeta);
        holder.setItem(gc.getSlot("rescission", "close"), close, p -> p.closeInventory());

        holder.fill();
        holder.open(viewer);
    }

    private static ItemStack createButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    private static String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}
