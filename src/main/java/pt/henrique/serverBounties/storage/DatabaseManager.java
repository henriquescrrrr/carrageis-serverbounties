package pt.henrique.serverbounties.storage;

import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.manager.BountyEntry;
import pt.henrique.serverbounties.model.ContractStatus;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.model.HitmanSettings;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the SQLite database for persistent storage.
 * All public methods return CompletableFuture — never block the main thread.
 */
public final class DatabaseManager {

    private final ServerBounties plugin;
    private final ExecutorService executor;
    private Connection connection;

    public DatabaseManager(ServerBounties plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ServerBounties-DB");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    public void init() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "data.db");
            if (!dbFile.getParentFile().exists()) {
                dbFile.getParentFile().mkdirs();
            }

            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // WAL mode for better concurrent reads
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA foreign_keys=ON;");
            }

            createTables();
            plugin.debug("Database initialized: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Bounty entries
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bounty_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_uuid TEXT NOT NULL,
                    contributor_uuid TEXT NOT NULL,
                    amount_cents BIGINT NOT NULL,
                    created_at BIGINT NOT NULL
                )
            """);

            // Index for fast lookups by target
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bounty_target
                ON bounty_entries (target_uuid)
            """);

            // Index for lookups by contributor
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bounty_contributor
                ON bounty_entries (contributor_uuid)
            """);

            // Hitman contracts — migrate old schema if needed
            migrateHitmanSchema(stmt);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS hitman_contracts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_uuid TEXT NOT NULL,
                    hitman_uuid TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    amount_cents BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    proposed_hours INTEGER NOT NULL DEFAULT 24,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL DEFAULT 0,
                    accepted_at BIGINT DEFAULT 0,
                    expires_at BIGINT DEFAULT 0,
                    completed_at BIGINT DEFAULT 0,
                    counter_value_cents BIGINT DEFAULT 0,
                    counter_hours INTEGER DEFAULT 0,
                    counter_from TEXT,
                    rescission_client_percent INTEGER DEFAULT 0,
                    rescission_proposed_by TEXT,
                    rescission_pending INTEGER DEFAULT 0
                )
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_hitman_target
                ON hitman_contracts (target_uuid, status)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_hitman_hitman
                ON hitman_contracts (hitman_uuid, status)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_hitman_client
                ON hitman_contracts (client_uuid, status)
            """);

            // Hitman settings
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS hitman_settings (
                    uuid TEXT PRIMARY KEY,
                    available INTEGER DEFAULT 0,
                    min_price_cents BIGINT DEFAULT 100000,
                    min_hours INTEGER DEFAULT 1,
                    max_hours INTEGER DEFAULT 168,
                    max_concurrent INTEGER DEFAULT 3
                )
            """);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing database: " + e.getMessage());
            }
        }
    }

    // ── Bounty Operations ────────────────────────────────────────────────

    /**
     * Insert a new bounty entry. Returns the generated entry ID.
     */
    public CompletableFuture<Integer> insertBountyEntry(UUID target, UUID contributor, long amountCents) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO bounty_entries (target_uuid, contributor_uuid, amount_cents, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, target.toString());
                ps.setString(2, contributor.toString());
                ps.setLong(3, amountCents);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
                return -1;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error inserting bounty entry: " + e.getMessage());
                return -1;
            }
        }, executor);
    }

    /**
     * Delete a specific bounty entry by ID.
     */
    public CompletableFuture<Boolean> deleteBountyEntry(int entryId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM bounty_entries WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, entryId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error deleting bounty entry: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    /**
     * Delete all bounty entries for a target.
     */
    public CompletableFuture<Boolean> deleteAllBountyEntries(UUID target) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM bounty_entries WHERE target_uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, target.toString());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error deleting bounty entries for " + target + ": " + e.getMessage());
                return false;
            }
        }, executor);
    }

    /**
     * Load all bounty entries from the database.
     * Used on startup to populate the cache.
     */
    public CompletableFuture<List<BountyEntry>> loadAllBountyEntries() {
        return CompletableFuture.supplyAsync(() -> {
            List<BountyEntry> entries = new ArrayList<>();
            String sql = "SELECT id, target_uuid, contributor_uuid, amount_cents, created_at FROM bounty_entries";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    entries.add(new BountyEntry(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("target_uuid")),
                            UUID.fromString(rs.getString("contributor_uuid")),
                            rs.getLong("amount_cents"),
                            rs.getLong("created_at")
                    ));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error loading bounty entries: " + e.getMessage());
            }
            plugin.debug("Loaded " + entries.size() + " bounty entries from database.");
            return entries;
        }, executor);
    }

    /**
     * Get all bounty entries for a specific target.
     */
    public CompletableFuture<List<BountyEntry>> getBountyEntriesForTarget(UUID target) {
        return CompletableFuture.supplyAsync(() -> {
            List<BountyEntry> entries = new ArrayList<>();
            String sql = "SELECT id, target_uuid, contributor_uuid, amount_cents, created_at FROM bounty_entries WHERE target_uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new BountyEntry(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("target_uuid")),
                                UUID.fromString(rs.getString("contributor_uuid")),
                                rs.getLong("amount_cents"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error loading bounty entries for target: " + e.getMessage());
            }
            return entries;
        }, executor);
    }

    // ── Hitman Schema Migration ──────────────────────────────────────────

    private void migrateHitmanSchema(Statement stmt) {
        try {
            // If the new column exists, schema is up to date
            stmt.execute("SELECT proposed_hours FROM hitman_contracts LIMIT 0");
        } catch (SQLException e) {
            // Old schema — drop and let createTables recreate
            try {
                stmt.execute("DROP TABLE IF EXISTS hitman_contracts");
                plugin.getLogger().info("Migrated hitman_contracts table to new schema.");
            } catch (SQLException ex) {
                plugin.getLogger().warning("Error migrating hitman schema: " + ex.getMessage());
            }
        }
    }

    // ── Hitman Contract Operations ───────────────────────────────────────

    public CompletableFuture<Integer> insertContract(UUID client, UUID hitman, UUID target,
                                                     long amountCents, int proposedHours) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO hitman_contracts
                (client_uuid, hitman_uuid, target_uuid, amount_cents, status, proposed_hours, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
            """;
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, client.toString());
                ps.setString(2, hitman.toString());
                ps.setString(3, target.toString());
                ps.setLong(4, amountCents);
                ps.setInt(5, proposedHours);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
                return -1;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error inserting hitman contract: " + e.getMessage());
                return -1;
            }
        }, executor);
    }

    /**
     * Persist all mutable fields of a contract to the database.
     */
    public CompletableFuture<Boolean> updateContract(HitmanContract c) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                UPDATE hitman_contracts SET
                    status = ?, updated_at = ?, accepted_at = ?, expires_at = ?, completed_at = ?,
                    counter_value_cents = ?, counter_hours = ?, counter_from = ?,
                    rescission_client_percent = ?, rescission_proposed_by = ?, rescission_pending = ?
                WHERE id = ?
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, c.getStatus().name());
                ps.setLong(2, c.getUpdatedAt());
                ps.setLong(3, c.getAcceptedAt());
                ps.setLong(4, c.getExpiresAt());
                ps.setLong(5, c.getCompletedAt());
                ps.setLong(6, c.getCounterValueCents());
                ps.setInt(7, c.getCounterHours());
                ps.setString(8, c.getCounterFrom());
                ps.setInt(9, c.getRescissionClientPercent());
                ps.setString(10, c.getRescissionProposedBy());
                ps.setInt(11, c.isRescissionPending() ? 1 : 0);
                ps.setInt(12, c.getId());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating hitman contract " + c.getId() + ": " + e.getMessage());
                return false;
            }
        }, executor);
    }

    /**
     * Load all non-terminal contracts from the database (for cache).
     */
    public CompletableFuture<List<HitmanContract>> loadAllNonTerminalContracts() {
        return CompletableFuture.supplyAsync(() -> {
            List<HitmanContract> contracts = new ArrayList<>();
            String sql = """
                SELECT * FROM hitman_contracts
                WHERE status IN ('PENDING','COUNTER_FROM_HITMAN','COUNTER_FROM_CLIENT','ACCEPTED')
            """;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    contracts.add(readContract(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error loading hitman contracts: " + e.getMessage());
            }
            plugin.debug("Loaded " + contracts.size() + " non-terminal hitman contracts from database.");
            return contracts;
        }, executor);
    }

    private HitmanContract readContract(ResultSet rs) throws SQLException {
        return new HitmanContract(
                rs.getInt("id"),
                UUID.fromString(rs.getString("client_uuid")),
                UUID.fromString(rs.getString("hitman_uuid")),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getLong("amount_cents"),
                ContractStatus.valueOf(rs.getString("status")),
                rs.getInt("proposed_hours"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("accepted_at"),
                rs.getLong("expires_at"),
                rs.getLong("completed_at"),
                rs.getLong("counter_value_cents"),
                rs.getInt("counter_hours"),
                rs.getString("counter_from"),
                rs.getInt("rescission_client_percent"),
                rs.getString("rescission_proposed_by"),
                rs.getInt("rescission_pending") == 1
        );
    }

    // ── Hitman Settings Operations ───────────────────────────────────────

    public CompletableFuture<HitmanSettings> loadHitmanSettings(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM hitman_settings WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new HitmanSettings(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getInt("available") == 1,
                                rs.getLong("min_price_cents"),
                                rs.getInt("min_hours"),
                                rs.getInt("max_hours"),
                                rs.getInt("max_concurrent")
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error loading hitman settings for " + uuid + ": " + e.getMessage());
            }
            return null;
        }, executor);
    }

    public CompletableFuture<List<HitmanSettings>> loadAllHitmanSettings() {
        return CompletableFuture.supplyAsync(() -> {
            List<HitmanSettings> list = new ArrayList<>();
            String sql = "SELECT * FROM hitman_settings";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(new HitmanSettings(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getInt("available") == 1,
                            rs.getLong("min_price_cents"),
                            rs.getInt("min_hours"),
                            rs.getInt("max_hours"),
                            rs.getInt("max_concurrent")
                    ));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error loading all hitman settings: " + e.getMessage());
            }
            return list;
        }, executor);
    }

    public CompletableFuture<Boolean> saveHitmanSettings(HitmanSettings s) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO hitman_settings (uuid, available, min_price_cents, min_hours, max_hours, max_concurrent)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    available = excluded.available,
                    min_price_cents = excluded.min_price_cents,
                    min_hours = excluded.min_hours,
                    max_hours = excluded.max_hours,
                    max_concurrent = excluded.max_concurrent
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, s.getUuid().toString());
                ps.setInt(2, s.isAvailable() ? 1 : 0);
                ps.setLong(3, s.getMinPriceCents());
                ps.setInt(4, s.getMinHours());
                ps.setInt(5, s.getMaxHours());
                ps.setInt(6, s.getMaxConcurrent());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error saving hitman settings for " + s.getUuid() + ": " + e.getMessage());
                return false;
            }
        }, executor);
    }
}

