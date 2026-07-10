package pt.henrique.serverbounties.gui;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import pt.henrique.serverbounties.ServerBounties;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central GUI event listener using the InventoryHolder pattern.
 * <p>
 * All GUI identity and click logic lives in {@link GuiHolder} — this listener
 * simply dispatches events to the appropriate handler stored on the holder.
 * No player metadata, no race conditions, no item theft.
 */
public final class GuiListener implements Listener {

    private final ServerBounties plugin;

    public GuiListener(ServerBounties plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder gui)) return;

        // ALWAYS cancel — prevent ALL item movement in any GuiHolder inventory
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        // Only process clicks from players, in the top inventory
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        // Play click sound
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);

        // Try rich handler first (left/right/shift click distinction)
        BiConsumer<Player, ClickType> rich = gui.getRichClickHandler(slot);
        if (rich != null) {
            rich.accept(player, event.getClick());
            return;
        }

        // Regular handler
        Consumer<Player> handler = gui.getClickHandler(slot);
        if (handler != null) {
            handler.accept(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder)) return;

        // If ANY dragged slot is in the top inventory, cancel the entire drag
        int topSize = top.getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
