package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.GuiConfig;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.model.HitmanSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Main menu GUI — central hub for all bounty and hitman features.
 */
public final class MainMenuGui {

    private MainMenuGui() {}

    public static void open(Player viewer) {
        ServerBounties plugin = ServerBounties.getInstance();
        LangManager lang = plugin.getLangManager();
        GuiConfig gc = plugin.getGuiConfig();
        HitmanManager hitmanMgr = plugin.getHitmanManager();

        int size = gc.getSize("main-menu");
        Component title = lang.get(viewer, "gui-main-menu-title");
        GuiHolder holder = new GuiHolder("main-menu", size, title);

        // ── Bounty section header ────────────────────────────────────────
        ItemStack bountyHeader = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta bhMeta = bountyHeader.getItemMeta();
        bhMeta.displayName(lang.get(viewer, "gui-header-bounties"));
        bountyHeader.setItemMeta(bhMeta);
        holder.setItem(gc.getSlot("main-menu", "bounty-header"), bountyHeader);

        // ── Bounty Board ─────────────────────────────────────────────────
        ItemStack bountyBoard = new ItemStack(Material.BOOK);
        ItemMeta bbMeta = bountyBoard.getItemMeta();
        bbMeta.displayName(lang.get(viewer, "gui-btn-bounty-board"));
        bbMeta.lore(List.of(lang.get(viewer, "gui-btn-bounty-board-lore")));
        bountyBoard.setItemMeta(bbMeta);
        holder.setItem(gc.getSlot("main-menu", "bounty-board"), bountyBoard,
                p -> BountyBoardGui.open(p, 0));

        // ── Place a Bounty ───────────────────────────────────────────────
        ItemStack placeBounty = new ItemStack(Material.GOLD_INGOT);
        ItemMeta pbMeta = placeBounty.getItemMeta();
        pbMeta.displayName(lang.get(viewer, "gui-btn-place-bounty"));
        pbMeta.lore(List.of(lang.get(viewer, "gui-btn-place-bounty-lore")));
        placeBounty.setItemMeta(pbMeta);
        holder.setItem(gc.getSlot("main-menu", "place-bounty"), placeBounty,
                p -> PlaceBountyGui.openSelectPlayer(p, 0));

        // ── My Bounties ──────────────────────────────────────────────────
        ItemStack myBounties = new ItemStack(Material.PAPER);
        ItemMeta mbMeta = myBounties.getItemMeta();
        mbMeta.displayName(lang.get(viewer, "gui-btn-my-bounties"));
        mbMeta.lore(List.of(lang.get(viewer, "gui-btn-my-bounties-lore")));
        myBounties.setItemMeta(mbMeta);
        holder.setItem(gc.getSlot("main-menu", "my-bounties"), myBounties,
                p -> MyBountiesGui.open(p, 0));

        // ── Hitman section header ────────────────────────────────────────
        ItemStack hitmanHeader = new ItemStack(Material.IRON_BLOCK);
        ItemMeta hhdrMeta = hitmanHeader.getItemMeta();
        hhdrMeta.displayName(lang.get(viewer, "gui-header-hitman"));
        hitmanHeader.setItemMeta(hhdrMeta);
        holder.setItem(gc.getSlot("main-menu", "hitman-header"), hitmanHeader);

        // ── Hitman Board ─────────────────────────────────────────────────
        ItemStack hitmanBoard = new ItemStack(Material.COMPASS);
        ItemMeta hbMeta = hitmanBoard.getItemMeta();
        hbMeta.displayName(lang.get(viewer, "gui-btn-hitman-board"));
        hbMeta.lore(List.of(lang.get(viewer, "gui-btn-hitman-board-lore")));
        hitmanBoard.setItemMeta(hbMeta);
        holder.setItem(gc.getSlot("main-menu", "hitman-board"), hitmanBoard,
                p -> HitmanBoardGui.open(p, 0));

        // ── Hire a Hitman ────────────────────────────────────────────────
        boolean hitmanPermission = viewer.hasPermission("serverbounties.hitman");
        if (hitmanPermission) {
            ItemStack hireHitman = new ItemStack(Material.IRON_SWORD);
            ItemMeta hhMeta = hireHitman.getItemMeta();
            hhMeta.displayName(lang.get(viewer, "gui-btn-hire-hitman"));
            hhMeta.lore(List.of(lang.get(viewer, "gui-btn-hire-hitman-lore")));
            hireHitman.setItemMeta(hhMeta);
            holder.setItem(gc.getSlot("main-menu", "hire-hitman"), hireHitman,
                    p -> HitmanGui.openSelectTarget(p, 0));
        } else {
            ItemStack disabled = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta dMeta = disabled.getItemMeta();
            dMeta.displayName(lang.get(viewer, "gui-btn-hitman-disabled"));
            dMeta.lore(List.of(lang.get(viewer, "gui-btn-hitman-disabled-lore")));
            disabled.setItemMeta(dMeta);
            holder.setItem(gc.getSlot("main-menu", "hire-hitman"), disabled);
        }

        // ── My Contracts ─────────────────────────────────────────────────
        UUID viewerUuid = viewer.getUniqueId();
        List<HitmanContract> allContracts = new ArrayList<>(hitmanMgr.getContractsForClient(viewerUuid));
        for (HitmanContract c : hitmanMgr.getAllContractsForHitman(viewerUuid)) {
            if (!allContracts.contains(c)) allContracts.add(c);
        }
        long activeOrPending = allContracts.stream()
                .filter(c -> c.getStatus().isActive() || c.getStatus().isNegotiating())
                .count();

        ItemStack myContracts = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta mcMeta = myContracts.getItemMeta();
        mcMeta.displayName(lang.get(viewer, "gui-btn-my-contracts"));
        List<Component> mcLore = new ArrayList<>();
        mcLore.add(lang.get(viewer, "gui-btn-my-contracts-lore"));
        if (activeOrPending > 0) {
            mcLore.add(lang.get(viewer, "gui-btn-my-contracts-active",
                    "count", String.valueOf(activeOrPending)));
        }
        mcMeta.lore(mcLore);
        myContracts.setItemMeta(mcMeta);
        holder.setItem(gc.getSlot("main-menu", "my-contracts"), myContracts,
                p -> HitmanGui.openMyContracts(p, 0));

        // ── Hitman Settings ──────────────────────────────────────────────
        HitmanSettings settings = hitmanMgr.getSettings(viewerUuid);
        ItemStack hitmanSettings = new ItemStack(Material.COMPARATOR);
        ItemMeta hsMeta = hitmanSettings.getItemMeta();
        hsMeta.displayName(lang.get(viewer, "gui-btn-hitman-settings"));
        List<Component> hsLore = new ArrayList<>();
        hsLore.add(lang.get(viewer, "gui-btn-hitman-settings-lore"));
        hsLore.add(settings.isAvailable()
                ? lang.get(viewer, "gui-toggle-available-on")
                : lang.get(viewer, "gui-toggle-available-off"));
        hsMeta.lore(hsLore);
        hitmanSettings.setItemMeta(hsMeta);
        holder.setItem(gc.getSlot("main-menu", "hitman-settings"), hitmanSettings,
                p -> HitmanSettingsGui.open(p));

        // ── Close ────────────────────────────────────────────────────────
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(lang.get(viewer, "gui-close"));
        close.setItemMeta(closeMeta);
        holder.setItem(gc.getSlot("main-menu", "close"), close, p -> p.closeInventory());

        holder.fill();
        holder.open(viewer);
    }
}
