package pt.henrique.serverbounties.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Custom InventoryHolder that carries GUI identity, click handlers, and arbitrary data.
 * Replaces all player-metadata-based GUI tracking. The GUI ID and handlers live on the
 * inventory itself — no race conditions on close/open transitions.
 */
public final class GuiHolder implements InventoryHolder {

    private final String guiId;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> clickHandlers = new HashMap<>();
    private final Map<Integer, BiConsumer<Player, ClickType>> richClickHandlers = new HashMap<>();
    private final Map<String, Object> data = new HashMap<>();

    public GuiHolder(String guiId, int size, Component title) {
        this.guiId = guiId;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    // ── Identity ─────────────────────────────────────────────────────────

    public String getGuiId() {
        return guiId;
    }

    // ── Item + Handler registration ──────────────────────────────────────

    /**
     * Set an item with a simple (Player) click handler.
     */
    public void setItem(int slot, ItemStack item, Consumer<Player> onClick) {
        if (isInvalidSlot(slot)) return;
        inventory.setItem(slot, applyFlags(item));
        clickHandlers.remove(slot);
        richClickHandlers.remove(slot);
        if (onClick != null) clickHandlers.put(slot, onClick);
    }

    /**
     * Set an item with a rich (Player, ClickType) handler for left/right/shift distinction.
     */
    public void setItem(int slot, ItemStack item, BiConsumer<Player, ClickType> onClick) {
        if (isInvalidSlot(slot)) return;
        inventory.setItem(slot, applyFlags(item));
        clickHandlers.remove(slot);
        richClickHandlers.remove(slot);
        if (onClick != null) richClickHandlers.put(slot, onClick);
    }

    /**
     * Set a decorative item with no click handler.
     */
    public void setItem(int slot, ItemStack item) {
        if (isInvalidSlot(slot)) return;
        inventory.setItem(slot, applyFlags(item));
    }

    /**
     * Guard against out-of-range slots (e.g. a mis-edited config where GuiConfig#getSlot
     * returns -1, or a slot beyond the inventory size). Prevents an
     * IndexOutOfBoundsException from crashing GUI construction; the item is simply skipped.
     */
    private boolean isInvalidSlot(int slot) {
        return slot < 0 || slot >= inventory.getSize();
    }

    private ItemStack applyFlags(ItemStack item) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_UNBREAKABLE
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Fill empty slots ─────────────────────────────────────────────────

    /**
     * Fill all empty (null) slots with gray glass panes.
     */
    public void fill() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.empty());
        filler.setItemMeta(fm);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    // ── Data storage (replaces player metadata) ──────────────────────────

    public void setData(String key, Object value) {
        data.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) data.get(key);
    }

    public int getIntData(String key, int def) {
        Object v = data.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public long getLongData(String key, long def) {
        Object v = data.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public String getStringData(String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : null;
    }

    // ── Handler retrieval (used by GuiListener) ──────────────────────────

    public Consumer<Player> getClickHandler(int slot) {
        return clickHandlers.get(slot);
    }

    public BiConsumer<Player, ClickType> getRichClickHandler(int slot) {
        return richClickHandlers.get(slot);
    }

    // ── Open ─────────────────────────────────────────────────────────────

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public @org.jetbrains.annotations.NotNull Inventory getInventory() {
        return inventory;
    }
}


