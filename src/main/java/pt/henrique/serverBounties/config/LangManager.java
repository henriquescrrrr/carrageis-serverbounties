package pt.henrique.serverbounties.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.hook.PlayerSettingsHook;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-locale language files with MiniMessage formatting.
 * <p>
 * Storage uses {@link YamlConfiguration} objects with {@code setDefaults()}
 * so that new keys added to the JAR resource automatically appear without
 * requiring the server admin to update their lang files.
 * <p>
 * Locale resolution: PlayerSettings (if available) → config global → en_US.
 */
public final class LangManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ServerBounties plugin;
    private final ConfigManager configManager;
    private final PlayerSettingsHook playerSettingsHook;

    /** locale code → loaded YamlConfiguration (with JAR defaults merged via setDefaults) */
    private final Map<String, YamlConfiguration> locales = new HashMap<>();

    public LangManager(ServerBounties plugin, ConfigManager configManager, PlayerSettingsHook playerSettingsHook) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.playerSettingsHook = playerSettingsHook;
    }

    // ── Loading ──────────────────────────────────────────────────────────

    public void load() {
        locales.clear();

        // 1. Ensure lang/ directory exists
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // 2. Save default lang files from JAR resources (won't overwrite existing)
        for (String locale : new String[]{"en_US", "pt_PT", "fr_FR"}) {
            plugin.saveResource("lang/" + locale + ".yml", false);
        }

        // 3. Load ALL .yml files from lang/ folder
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String locale = file.getName().replace(".yml", "");
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

                // Merge with defaults from JAR — getString() will auto-check defaults
                InputStream defaultStream = plugin.getResource("lang/" + locale + ".yml");
                if (defaultStream != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                    yaml.setDefaults(defaults);
                }

                locales.put(locale, yaml);
                plugin.getLogger().info("Loaded language: " + locale);
            }
        }

        // 4. Validate fallback chain
        String defaultLocale = configManager.getGlobalLanguage();
        if (!locales.containsKey(defaultLocale)) {
            plugin.getLogger().warning("Default language '" + defaultLocale
                    + "' not found! Falling back to en_US.");
        }
        if (!locales.containsKey("en_US")) {
            plugin.getLogger().severe("en_US language file not found! Language system may not work correctly.");
        }
    }

    // ── Locale resolution ────────────────────────────────────────────────

    /**
     * Resolve the locale for a player.
     * Tries PlayerSettings first, then falls back to config global.
     */
    public String getLocale(UUID playerUuid) {
        if (playerSettingsHook != null && playerSettingsHook.isAvailable()) {
            String psLocale = playerSettingsHook.getLanguage(playerUuid);
            if (psLocale != null && locales.containsKey(psLocale)) {
                return psLocale;
            }
        }
        return configManager.getGlobalLanguage();
    }

    /**
     * Convenience overload accepting a Player.
     */
    public String getLocale(Player player) {
        return getLocale(player.getUniqueId());
    }

    // ── Raw string access ────────────────────────────────────────────────

    /**
     * Get the raw MiniMessage string for a key, resolved to the player's locale.
     */
    public String getRaw(UUID playerUuid, String key) {
        return getRawForLocale(getLocale(playerUuid), key);
    }

    /**
     * Convenience overload accepting a Player.
     */
    public String getRaw(Player player, String key) {
        return getRawForLocale(getLocale(player), key);
    }

    /**
     * Get the raw MiniMessage string for a key in a specific locale.
     * Falls back: requested locale → config global → en_US.
     */
    public String getRawForLocale(String locale, String key) {
        YamlConfiguration yaml = locales.get(locale);
        if (yaml == null) yaml = locales.get(configManager.getGlobalLanguage());
        if (yaml == null) yaml = locales.get("en_US");
        if (yaml == null) return "<red>Missing language data</red>";

        // getString() automatically checks defaults set via setDefaults()
        String value = yaml.getString(key);
        if (value == null) {
            return "<red>Missing: " + key + "</red>";
        }

        // Inline {prefix} replacement
        if (value.contains("{prefix}")) {
            String prefix = yaml.getString("prefix", "");
            value = value.replace("{prefix}", prefix);
        }

        return value;
    }

    // ── Component access (MiniMessage parsed) ────────────────────────────

    /**
     * Get a parsed Component for a key with placeholder replacements, by UUID.
     */
    public Component get(UUID playerUuid, String key, String... replacements) {
        String raw = getRaw(playerUuid, key);
        raw = applyReplacements(raw, replacements);
        return MINI.deserialize(raw);
    }

    /**
     * Get a parsed Component for a key with placeholder replacements, by Player.
     */
    public Component get(Player player, String key, String... replacements) {
        return get(player.getUniqueId(), key, replacements);
    }

    /**
     * Alias for {@link #get(Player, String, String...)} — matches the Phase 4 API naming.
     */
    public Component getMessage(Player player, String key, String... replacements) {
        return get(player, key, replacements);
    }

    /**
     * Get a parsed Component using the global locale (no player context).
     */
    public Component getGlobal(String key, String... replacements) {
        String raw = getRawForLocale(configManager.getGlobalLanguage(), key);
        raw = applyReplacements(raw, replacements);
        return MINI.deserialize(raw);
    }

    /**
     * Parse a raw MiniMessage string into a Component.
     */
    public Component parse(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    // ── Replacement utility ──────────────────────────────────────────────

    /**
     * Apply key-value replacements. Expects pairs: key1, value1, key2, value2, ...
     * <p>
     * Replacement VALUES are player-influenced (names, amounts) and are inserted into a
     * MiniMessage template that is subsequently parsed. To prevent MiniMessage injection
     * (a player name containing e.g. {@code <click>}/{@code <rainbow>}/{@code <red>} on
     * offline-mode / Geyser / nick-plugin servers would otherwise render as live markup in
     * server-wide broadcasts), every value has its MiniMessage tags escaped so it renders as
     * literal text. The template's own tags are untouched.
     */
    private String applyReplacements(String raw, String... replacements) {
        if (replacements.length % 2 != 0) {
            plugin.getLogger().warning("LangManager: Odd number of replacement args for: " + raw);
            return raw;
        }
        for (int i = 0; i < replacements.length; i += 2) {
            String value = replacements[i + 1];
            raw = raw.replace("{" + replacements[i] + "}", escapeValue(value));
        }
        return raw;
    }

    /**
     * Escape a player-influenced replacement value so any MiniMessage tags it contains are
     * rendered literally instead of parsed. Null-safe.
     */
    public String escapeValue(String value) {
        if (value == null) return "";
        return MINI.escapeTags(value);
    }

    // ── Data access ──────────────────────────────────────────────────────

    /**
     * Get the loaded YamlConfiguration objects (for inspection/testing).
     */
    public Map<String, YamlConfiguration> getLocales() {
        return locales;
    }
}
