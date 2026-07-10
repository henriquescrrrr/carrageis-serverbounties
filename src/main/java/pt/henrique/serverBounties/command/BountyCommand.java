package pt.henrique.serverbounties.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.economy.EconomyManager;
import pt.henrique.serverbounties.gui.BountyBoardGui;
import pt.henrique.serverbounties.gui.BountyDetailGui;
import pt.henrique.serverbounties.gui.MainMenuGui;
import pt.henrique.serverbounties.gui.MyBountiesGui;
import pt.henrique.serverbounties.gui.PlaceBountyGui;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.notification.NotificationManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Brigadier-based /bounty command registration.
 *
 * /bounty                → opens PlaceBountyGui (player select)
 * /bounty board          → opens BountyBoardGui
 * /bounty check <player> → opens BountyDetailGui
 * /bounty my             → opens MyBountiesGui
 * /bounty top            → sends top 10 in chat
 * /bounty remove <player>→ admin: remove all bounties
 * /bounty reload         → admin: reload config+lang
 *
 * Alias: /bounties → same as /bounty board
 */
@SuppressWarnings("UnstableApiUsage")
public final class BountyCommand {

    private final ServerBounties plugin;

    public BountyCommand(ServerBounties plugin) {
        this.plugin = plugin;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            // Build /bounty command tree
            LiteralCommandNode<CommandSourceStack> bountyNode = Commands.literal("bounty")
                    .requires(src -> src.getSender().hasPermission("serverbounties.bounty"))
                    // /bounty (no args) → opens MainMenuGui
                    .executes(ctx -> {
                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                            ctx.getSource().getSender().sendMessage(
                                    plugin.getLangManager().getGlobal("player-only"));
                            return Command.SINGLE_SUCCESS;
                        }
                        MainMenuGui.open(player);
                        return Command.SINGLE_SUCCESS;
                    })
                    // /bounty place → opens PlaceBountyGui directly
                    .then(Commands.literal("place")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                PlaceBountyGui.openSelectPlayer(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /bounty board
                    .then(Commands.literal("board")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                BountyBoardGui.open(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /bounty check <player>
                    .then(Commands.literal("check")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        String input = builder.getRemainingLowerCase();
                                        for (Player p : Bukkit.getOnlinePlayers()) {
                                            if (p.getName().toLowerCase().startsWith(input)) {
                                                builder.suggest(p.getName());
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage(
                                                    plugin.getLangManager().getGlobal("player-only"));
                                            return Command.SINGLE_SUCCESS;
                                        }
                                        String targetName = StringArgumentType.getString(ctx, "player");
                                        return handleCheck(player, targetName);
                                    })
                            )
                    )
                    // /bounty my
                    .then(Commands.literal("my")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                MyBountiesGui.open(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /bounty top
                    .then(Commands.literal("top")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                return handleTop(player);
                            })
                    )
                    // /bounty remove <player> (admin)
                    .then(Commands.literal("remove")
                            .requires(src -> src.getSender().hasPermission("serverbounties.admin"))
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        String input = builder.getRemainingLowerCase();
                                        for (Player p : Bukkit.getOnlinePlayers()) {
                                            if (p.getName().toLowerCase().startsWith(input)) {
                                                builder.suggest(p.getName());
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String targetName = StringArgumentType.getString(ctx, "player");
                                        return handleRemove(ctx.getSource().getSender(), targetName);
                                    })
                            )
                    )
                    // /bounty reload (admin)
                    .then(Commands.literal("reload")
                            .requires(src -> src.getSender().hasPermission("serverbounties.admin"))
                            .executes(ctx -> {
                                plugin.reload();
                                ctx.getSource().getSender().sendMessage(
                                        plugin.getLangManager().getGlobal("admin-reload"));
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .build();

            commands.register(bountyNode, "Main bounty command",
                    List.of("bounties"));
        });
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private int handleCheck(Player player, String targetName) {
        LangManager lang = plugin.getLangManager();

        @SuppressWarnings("deprecation")
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            // Try offline
            @SuppressWarnings("deprecation")
            UUID offlineUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            if (plugin.getBountyManager().hasBounty(offlineUuid)) {
                BountyDetailGui.open(player, offlineUuid, 0);
            } else {
                player.sendMessage(lang.get(player, "bounty-no-bounty", "target", targetName));
            }
            return Command.SINGLE_SUCCESS;
        }

        if (plugin.getBountyManager().hasBounty(target.getUniqueId())) {
            BountyDetailGui.open(player, target.getUniqueId(), 0);
        } else {
            player.sendMessage(lang.get(player, "bounty-no-bounty", "target", target.getName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int handleTop(Player player) {
        LangManager lang = plugin.getLangManager();
        EconomyManager econ = plugin.getEconomyManager();
        BountyManager bountyMgr = plugin.getBountyManager();

        Map<UUID, Long> all = bountyMgr.getAllBounties();
        if (all.isEmpty()) {
            player.sendMessage(lang.get(player, "bounty-none-active"));
            return Command.SINGLE_SUCCESS;
        }

        player.sendMessage(Component.text(""));
        player.sendMessage(lang.get(player, "gui-board-title"));
        player.sendMessage(Component.text(""));

        int rank = 1;
        for (Map.Entry<UUID, Long> entry : all.entrySet()) {
            if (rank > 10) break;
            String name = getPlayerName(entry.getKey());
            String amount = econ.formatCurrency(entry.getValue());
            int contributors = bountyMgr.getContributorCount(entry.getKey());
            player.sendMessage(Component.text("§e#" + rank + " §f" + name + " §7- §f" + amount
                    + " §8(" + contributors + " contributor" + (contributors != 1 ? "s" : "") + ")"));
            rank++;
        }

        player.sendMessage(Component.text(""));
        return Command.SINGLE_SUCCESS;
    }

    private int handleRemove(org.bukkit.command.CommandSender sender, String targetName) {
        LangManager lang = plugin.getLangManager();
        NotificationManager notif = plugin.getNotificationManager();

        @SuppressWarnings("deprecation")
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();

        plugin.getBountyManager().removeBountyWithRefund(targetUuid)
                .thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player p) {
                        p.sendMessage(lang.get(p, "admin-removed", "target", targetName));
                    } else {
                        sender.sendMessage(lang.getGlobal("admin-removed", "target", targetName));
                    }
                    // Remove BossBar
                    notif.removeBossBarByUuid(targetUuid);
                }));

        return Command.SINGLE_SUCCESS;
    }

    private String getPlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "Unknown";
    }
}

