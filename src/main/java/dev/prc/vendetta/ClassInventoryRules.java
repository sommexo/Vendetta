package dev.prc.vendetta;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Enforces class-based inventory restrictions without changing unrelated items. */
final class ClassInventoryRules implements Listener {
    private final mark plugin;
    private final Set<UUID> queued = new HashSet<>();

    ClassInventoryRules(mark plugin) { this.plugin = plugin; }

    static boolean prohibited(String playerClass, Material material) {
        return "vanguard".equals(playerClass) && material == Material.MACE;
    }

    void enforce(Player player) {
        if (!player.isOnline() || !"vanguard".equals(plugin.selectedClass(player.getUniqueId()))) return;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.MACE) {
                ItemStack ejected = item.clone();
                inventory.setItem(slot, null);
                drop(player, ejected);
            }
        }
        for (int slot = 0; slot < inventory.getArmorContents().length; slot++) {
            ItemStack item = inventory.getArmorContents()[slot];
            if (item != null && item.getType() == Material.MACE) {
                inventory.setArmorContents(clearArmorSlot(inventory.getArmorContents(), slot));
                drop(player, item.clone());
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand.getType() == Material.MACE) {
            inventory.setItemInOffHand(null);
            drop(player, offhand.clone());
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == Material.MACE) {
            player.setItemOnCursor(null);
            drop(player, cursor.clone());
        }
    }

    private ItemStack[] clearArmorSlot(ItemStack[] armor, int slot) {
        armor[slot] = null;
        return armor;
    }

    private void drop(Player player, ItemStack item) {
        player.getWorld().dropItem(player.getLocation().add(0, 0.25, 0), item);
    }

    private void queue(Player player) {
        UUID id = player.getUniqueId();
        if (!queued.add(id)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            queued.remove(id);
            enforce(player);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { queue(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) queue(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) queue(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && prohibited(plugin.selectedClass(player.getUniqueId()), event.getRecipe().getResult().getType()))
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && prohibited(plugin.selectedClass(player.getUniqueId()), event.getItem().getItemStack().getType())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!(event.getDestination().getHolder() instanceof Player player)
                || !prohibited(plugin.selectedClass(player.getUniqueId()), event.getItem().getType())) return;
        event.setCancelled(true);
    }
}
