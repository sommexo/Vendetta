package dev.prc.vendetta;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Maps current vanilla inputs to ability service calls; a future mod input adapter can replace this class. */
final class VanguardActivationHandler implements Listener {
    private final mark plugin;
    private final VanguardProgression progression;
    private final VanguardAbilityListener abilities;

    VanguardActivationHandler(mark plugin, VanguardProgression progression, VanguardAbilityListener abilities) {
        this.plugin = plugin;
        this.progression = progression;
        this.abilities = abilities;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (plugin.isRelicDisabled(player.getUniqueId())) return;
        if (!progression.get(player.getUniqueId()).vanguard) return;
        if (event.getItem() == null || !event.getItem().getType().name().endsWith("_SWORD")) return;
        abilities.beginBloodthirstWindow(player);
    }

    @EventHandler
    public void onTotemPop(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !progression.get(player.getUniqueId()).vanguard) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) abilities.activateUltimate(player, true);
        });
    }
}
