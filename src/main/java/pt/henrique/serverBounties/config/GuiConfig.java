package pt.henrique.serverbounties.config;

import org.bukkit.configuration.file.FileConfiguration;
import pt.henrique.serverbounties.ServerBounties;

/**
 * Reads all GUI layout information (sizes, slot positions, items-per-page)
 * from the {@code gui} section of config.yml. Every GUI class uses this
 * instead of hardcoded slot constants.
 */
public final class GuiConfig {

    private final ServerBounties plugin;

    public GuiConfig(ServerBounties plugin) {
        this.plugin = plugin;
    }

    // ── Core accessors ───────────────────────────────────────────────────

    /**
     * Get the inventory size for a GUI (must be a multiple of 9).
     * Falls back to 27 if not specified.
     */
    public int getSize(String guiName) {
        return cfg().getInt("gui." + guiName + ".size", 27);
    }

    /**
     * Get a named slot position for a GUI.
     * Returns -1 if not configured (caller should check and skip).
     */
    public int getSlot(String guiName, String slotName) {
        return cfg().getInt("gui." + guiName + ".slots." + slotName, -1);
    }

    /**
     * Get the number of content items per page for paginated GUIs.
     * Falls back to 45 (5 rows).
     */
    public int getItemsPerPage(String guiName) {
        return cfg().getInt("gui." + guiName + ".items-per-page", 45);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }
}

