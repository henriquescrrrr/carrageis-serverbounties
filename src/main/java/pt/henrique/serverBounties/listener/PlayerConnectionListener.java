package pt.henrique.serverbounties.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.notification.NotificationManager;

/**
 * Handles player join/quit for BossBar management.
 */
public final class PlayerConnectionListener implements Listener {

    private final NotificationManager notificationManager;

    public PlayerConnectionListener(ServerBounties plugin) {
        this.notificationManager = plugin.getNotificationManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        notificationManager.onPlayerLogin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        notificationManager.onPlayerQuit(event.getPlayer());
    }
}

