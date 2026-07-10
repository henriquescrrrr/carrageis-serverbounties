package pt.henrique.serverbounties.listener;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import pt.henrique.serverbounties.ServerBounties;
import pt.henrique.serverbounties.manager.BountyManager;
import pt.henrique.serverbounties.manager.DamageTracker;
import pt.henrique.serverbounties.model.HitmanContract;
import pt.henrique.serverbounties.manager.HitmanManager;
import pt.henrique.serverbounties.notification.NotificationManager;

/**
 * Feeds the DamageTracker on EntityDamageByEntityEvent.
 * Also shows bounty hit notification when a player hits a bounty target.
 */
public final class DamageListener implements Listener {

    private final ServerBounties plugin;
    private final DamageTracker damageTracker;
    private final BountyManager bountyManager;
    private final HitmanManager hitmanManager;
    private final NotificationManager notificationManager;

    public DamageListener(ServerBounties plugin) {
        this.plugin = plugin;
        this.damageTracker = plugin.getDamageTracker();
        this.bountyManager = plugin.getBountyManager();
        this.hitmanManager = plugin.getHitmanManager();
        this.notificationManager = plugin.getNotificationManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;

        Player attacker = resolveAttacker(event);

        if (attacker == null || attacker.equals(target)) return;

        double damage = event.getFinalDamage();
        if (damage <= 0) return;

        // Record damage for proportional bounty distribution
        damageTracker.recordDamage(target.getUniqueId(), attacker.getUniqueId(), damage);

        // Bounty hit notification (action bar)
        if (bountyManager.hasBounty(target.getUniqueId())) {
            long totalBounty = bountyManager.getTotalBounty(target.getUniqueId());
            notificationManager.showHitBountyNotification(attacker, target.getUniqueId(), totalBounty);
        }

        // Hitman contract hit notification (action bar)
        HitmanContract contract = hitmanManager.getActiveContractForHitmanOnTarget(
                attacker.getUniqueId(), target.getUniqueId());
        if (contract != null) {
            notificationManager.showHitContractNotification(attacker, target.getUniqueId(), contract.getCurrentAmount());
        }
    }

    /**
     * Resolve the actual player attacker from a damage event.
     * Handles direct hits, projectiles, wolves, TNT, etc.
     */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        // Direct hit
        if (event.getDamager() instanceof Player p) {
            return p;
        }

        // Projectile (arrow, trident, etc.)
        if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }

        // Tamed wolf
        if (event.getDamager() instanceof org.bukkit.entity.Tameable tameable
                && tameable.isTamed()
                && tameable.getOwner() instanceof Player p) {
            return p;
        }

        // TNT ignited by player
        if (event.getDamager() instanceof org.bukkit.entity.TNTPrimed tnt
                && tnt.getSource() instanceof Player p) {
            return p;
        }

        return null;
    }
}

