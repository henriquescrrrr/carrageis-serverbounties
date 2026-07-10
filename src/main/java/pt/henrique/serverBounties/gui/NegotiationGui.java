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
import pt.henrique.serverbounties.model.ContractStatus;
import pt.henrique.serverbounties.model.HitmanContract;

import java.util.List;
import java.util.UUID;

/**
 * GUI for responding to hitman contract proposals and counter-offers.
 */
public final class NegotiationGui {

    private NegotiationGui() {}

    public static void open(Player viewer, int contractId) {
        ServerBounties plugin = ServerBounties.getInstance();
        HitmanManager hitmanMgr = plugin.getHitmanManager();
        EconomyManager econ = plugin.getEconomyManager();

        HitmanContract contract = hitmanMgr.getContractById(contractId);
        if (contract == null || !contract.getStatus().isNegotiating()) {
            viewer.sendMessage(plugin.getLangManager().get(viewer, "hitman-no-contracts"));
            return;
        }

        // Async balance check before opening
        econ.getBalanceCents(contract.getClientUuid()).thenAccept(clientBalance ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    buildAndOpen(viewer, contract, clientBalance);
                })
        ).exceptionally(ex -> {
            plugin.getLogger().warning("Error checking balance for NegotiationGui: " + ex.getMessage());
            Bukkit.getScheduler().runTask(plugin, () ->
                    buildAndOpen(viewer, contract, 0L));
            return null;
        });
    }

    private static void buildAndOpen(Player viewer, HitmanContract contract, long clientBalance) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        EconomyManager econ = plugin.getEconomyManager();

        UUID viewerUuid = viewer.getUniqueId();
        boolean isHitman = viewerUuid.equals(contract.getHitmanUuid());
        boolean isClient = viewerUuid.equals(contract.getClientUuid());
        ContractStatus status = contract.getStatus();

        long amount = contract.getCurrentAmount();
        int hours = contract.getCurrentHours();
        String targetName = getPlayerName(contract.getTargetUuid());
        String hitmanName = getPlayerName(contract.getHitmanUuid());
        String clientName = getPlayerName(contract.getClientUuid());

        String senderName;
        UUID senderUuid;
        if (status == ContractStatus.PENDING) {
            senderName = clientName;
            senderUuid = contract.getClientUuid();
        } else if (status == ContractStatus.COUNTER_FROM_HITMAN) {
            senderName = hitmanName;
            senderUuid = contract.getHitmanUuid();
        } else {
            senderName = clientName;
            senderUuid = contract.getClientUuid();
        }

        int size = gc.getSize("negotiation");
        Component title = lang.get(viewer, "gui-negotiation-title");
        GuiHolder holder = new GuiHolder("negotiation", size, title);
        holder.setData("contractId", contract.getId());

        // Contract info
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(lang.get(viewer, "gui-negotiation-info",
                "id", String.valueOf(contract.getId()),
                "status", status.name()));
        infoMeta.lore(List.of(
                lang.get(viewer, "gui-negotiation-from", "player", senderName)
        ));
        info.setItemMeta(infoMeta);
        holder.setItem(gc.getSlot("negotiation", "contract-info"), info);

        // Target head
        ItemStack targetHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta targetMeta = (SkullMeta) targetHead.getItemMeta();
        targetMeta.setOwningPlayer(Bukkit.getOfflinePlayer(contract.getTargetUuid()));
        targetMeta.displayName(lang.get(viewer, "gui-negotiation-target", "target", targetName));
        targetHead.setItemMeta(targetMeta);
        holder.setItem(gc.getSlot("negotiation", "target-head"), targetHead);

        // Current amount
        ItemStack amountItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta amountMeta = amountItem.getItemMeta();
        amountMeta.displayName(lang.get(viewer, "gui-negotiation-current-amount",
                "amount", econ.formatCurrency(amount)));
        amountItem.setItemMeta(amountMeta);
        holder.setItem(gc.getSlot("negotiation", "current-amount"), amountItem);

        // Current hours
        ItemStack hoursItem = new ItemStack(Material.CLOCK);
        ItemMeta hoursMeta = hoursItem.getItemMeta();
        hoursMeta.displayName(lang.get(viewer, "gui-negotiation-current-hours",
                "hours", String.valueOf(hours)));
        hoursItem.setItemMeta(hoursMeta);
        holder.setItem(gc.getSlot("negotiation", "current-hours"), hoursItem);

        // Sender head
        ItemStack senderHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta senderMeta = (SkullMeta) senderHead.getItemMeta();
        senderMeta.setOwningPlayer(Bukkit.getOfflinePlayer(senderUuid));
        senderMeta.displayName(lang.get(viewer, "gui-negotiation-from", "player", senderName));
        senderHead.setItemMeta(senderMeta);
        holder.setItem(gc.getSlot("negotiation", "sender-head"), senderHead);

        // Action buttons (only if viewer can respond)
        boolean canRespond =
                (isHitman && status.awaitingHitman()) ||
                (isClient && status.awaitingClient());

        final int cId = contract.getId();
        if (canRespond) {
            // Accept
            boolean clientCanAfford = clientBalance >= amount;
            if (clientCanAfford) {
                ItemStack accept = new ItemStack(Material.GREEN_WOOL);
                ItemMeta acceptMeta = accept.getItemMeta();
                acceptMeta.displayName(lang.get(viewer, "gui-negotiation-accept"));
                accept.setItemMeta(acceptMeta);
                holder.setItem(gc.getSlot("negotiation", "accept"), accept, p -> {
                    p.closeInventory();
                    HitmanManager hitmanMgr = plugin.getHitmanManager();
                    hitmanMgr.acceptContract(cId, p.getUniqueId())
                            .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                                switch (result) {
                                    case SUCCESS -> p.sendMessage(lang.get(p, "hitman-contract-accepted"));
                                    case CANNOT_AFFORD -> p.sendMessage(lang.get(p, "hitman-cant-afford",
                                            "amount", econ.formatCurrency(amount),
                                            "balance", econ.formatCurrency(clientBalance)));
                                    default -> p.sendMessage(lang.get(p, "hitman-economy-error"));
                                }
                            }));
                });
            } else {
                ItemStack disabled = new ItemStack(Material.GRAY_WOOL);
                ItemMeta disabledMeta = disabled.getItemMeta();
                disabledMeta.displayName(lang.get(viewer, "gui-negotiation-accept-disabled"));
                disabledMeta.lore(List.of(
                        lang.get(viewer, "gui-negotiation-accept-disabled-lore",
                                "amount", econ.formatCurrency(amount),
                                "balance", econ.formatCurrency(clientBalance))
                ));
                disabled.setItemMeta(disabledMeta);
                holder.setItem(gc.getSlot("negotiation", "accept"), disabled);
            }

            // Counter
            ItemStack counter = new ItemStack(Material.YELLOW_WOOL);
            ItemMeta counterMeta = counter.getItemMeta();
            counterMeta.displayName(lang.get(viewer, "gui-negotiation-counter"));
            counterMeta.lore(List.of(lang.get(viewer, "gui-negotiation-counter-lore")));
            counter.setItemMeta(counterMeta);
            holder.setItem(gc.getSlot("negotiation", "counter"), counter,
                    p -> CounterOfferGui.open(p, cId));

            // Decline
            ItemStack decline = new ItemStack(Material.RED_WOOL);
            ItemMeta declineMeta = decline.getItemMeta();
            declineMeta.displayName(lang.get(viewer, "gui-negotiation-decline"));
            decline.setItemMeta(declineMeta);
            holder.setItem(gc.getSlot("negotiation", "decline"), decline, p -> {
                p.closeInventory();
                HitmanManager hitmanMgr = plugin.getHitmanManager();
                hitmanMgr.declineContract(cId, p.getUniqueId());
                p.sendMessage(lang.get(p, "hitman-contract-declined"));
            });
        }

        // Back
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(lang.get(viewer, "gui-back"));
        back.setItemMeta(backMeta);
        holder.setItem(gc.getSlot("negotiation", "back"), back,
                p -> HitmanGui.openMyContracts(p, 0));

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

