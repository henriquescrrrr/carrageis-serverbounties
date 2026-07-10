package pt.henrique.serverbounties.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.config.LangManager;
import pt.henrique.serverbounties.gui.HitmanBoardGui;
import pt.henrique.serverbounties.gui.HitmanGui;
import pt.henrique.serverbounties.gui.HitmanSettingsGui;
import pt.henrique.serverbounties.gui.MainMenuGui;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.model.HitmanSettings;

import java.util.List;

/**
 * Brigadier-based /hitman command registration.
 *
 * /hitman              → opens HitmanGui (select target flow)
 * /hitman my           → opens My Contracts (combined client + hitman view)
 * /hitman contract     → opens Active Contract detail
 * /hitman settings     → opens HitmanSettingsGui
 * /hitman board        → opens HitmanBoardGui
 * /hitman hire <name>  → opens contract creation with hitman pre-selected
 * /hitman register     → toggles hitman availability
 *
 * Alias: /contract → same as /hitman contract
 */
@SuppressWarnings("UnstableApiUsage")
public final class HitmanCommand {

    private final ServerBounties plugin;

    public HitmanCommand(ServerBounties plugin) {
        this.plugin = plugin;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            LiteralCommandNode<CommandSourceStack> hitmanNode = Commands.literal("hitman")
                    .requires(src -> src.getSender().hasPermission("serverbounties.hitman"))
                    // /hitman (no args) → open MainMenuGui
                    .executes(ctx -> {
                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                            ctx.getSource().getSender().sendMessage(
                                    plugin.getLangManager().getGlobal("player-only"));
                            return Command.SINGLE_SUCCESS;
                        }
                        MainMenuGui.open(player);
                        return Command.SINGLE_SUCCESS;
                    })
                    // /hitman my → combined contracts view
                    .then(Commands.literal("my")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanGui.openMyContracts(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /hitman contracts → alias for /hitman my
                    .then(Commands.literal("contracts")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanGui.openMyContracts(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /hitman contract → hitman's active contract detail
                    .then(Commands.literal("contract")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanGui.openActiveContract(player);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /hitman settings → open hitman settings GUI
                    .then(Commands.literal("settings")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanSettingsGui.open(player);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /hitman board → open hitman board GUI
                    .then(Commands.literal("board")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanBoardGui.open(player, 0);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // /hitman hire <hitman> → open contract creation with hitman pre-selected
                    .then(Commands.literal("hire")
                            .then(Commands.argument("hitman", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        HitmanManager hitmanMgr = plugin.getHitmanManager();
                                        String input = builder.getRemainingLowerCase();
                                        for (Player online : Bukkit.getOnlinePlayers()) {
                                            if (!hitmanMgr.isAvailable(online.getUniqueId())) continue;
                                            if (online.getName().toLowerCase().startsWith(input)) {
                                                builder.suggest(online.getName());
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
                                        String hitmanName = StringArgumentType.getString(ctx, "hitman");
                                        Player hitman = Bukkit.getPlayer(hitmanName);
                                        LangManager lang = plugin.getLangManager();
                                        if (hitman == null || !hitman.isOnline()) {
                                            player.sendMessage(lang.get(player, "hitman-offline"));
                                            return Command.SINGLE_SUCCESS;
                                        }
                                        if (!plugin.getHitmanManager().isAvailable(hitman.getUniqueId())) {
                                            player.sendMessage(lang.get(player, "hitman-offline"));
                                            return Command.SINGLE_SUCCESS;
                                        }
                                        // Open target selection with hitman pre-selected
                                        HitmanGui.openSelectTarget(player, 0, hitman.getUniqueId());
                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
                    )
                    // /hitman register → toggle availability
                    .then(Commands.literal("register")
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(
                                            plugin.getLangManager().getGlobal("player-only"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                HitmanManager hitmanMgr = plugin.getHitmanManager();
                                LangManager lang = plugin.getLangManager();
                                HitmanSettings settings = hitmanMgr.getSettings(player.getUniqueId());

                                boolean newState = !settings.isAvailable();
                                settings.setAvailable(newState);
                                hitmanMgr.updateSettings(settings);

                                if (newState) {
                                    player.sendMessage(lang.get(player, "hitman-registered"));
                                } else {
                                    player.sendMessage(lang.get(player, "hitman-unregistered"));
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .build();

            commands.register(hitmanNode, "Hitman contract command",
                    List.of("contract"));
        });
    }
}

