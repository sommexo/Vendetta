package dev.prc.vendetta;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ClassSelectionService implements Listener {
    enum PlayerClass { VANGUARD, PHANTOM, WARDEN }
    private final mark plugin;
    private final VanguardProgression vanguard;
    private final File file;
    private final YamlConfiguration data;

    ClassSelectionService(mark plugin, VanguardProgression vanguard) {
        this.plugin = plugin;
        this.vanguard = vanguard;
        file = new File(plugin.getDataFolder(), "player-classes.yml");
        data = YamlConfiguration.loadConfiguration(file);
    }

    PlayerClass get(UUID uuid) {
        String raw = data.getString(uuid.toString());
        if (raw == null && vanguard.get(uuid).vanguard) return PlayerClass.VANGUARD;
        if (raw == null) return null;
        try { return PlayerClass.valueOf(raw); } catch (IllegalArgumentException ex) { return null; }
    }

    boolean choose(UUID uuid, String raw, boolean override) {
        PlayerClass selected;
        try { selected = PlayerClass.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return false; }
        if (!override && get(uuid) != null && !plugin.classChangerEnabled()) return false;
        data.set(uuid.toString(), selected.name());
        vanguard.get(uuid).vanguard = selected == PlayerClass.VANGUARD;
        save();
        vanguard.save(uuid);
        return true;
    }

    String className(UUID uuid) { PlayerClass type = get(uuid); return type == null ? null : type.name().toLowerCase(Locale.ROOT); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (get(player.getUniqueId()) != null) {
            if (!data.contains(player.getUniqueId().toString())) choose(player.getUniqueId(), "vanguard", true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> open(player));
    }

    void open(Player player) {
        if (!player.isOnline() || get(player.getUniqueId()) != null) return;
        Inventory inventory = Bukkit.createInventory(new SelectionHolder(), 9, ChatColor.DARK_PURPLE + "Choose Your Class");
        inventory.setItem(2, choice(Material.DIAMOND_SWORD, "§c§lVanguard", "§7Aggressive combat, counters, and", "§7the BLOOD BLOOD BLOOD ultimate."));
        inventory.setItem(4, choice(Material.ENCHANTED_GOLDEN_APPLE, "§5§lPhantom", "§7A swift, elusive class.", "§7(Abilities will be introduced later.)"));
        inventory.setItem(6, choice(Material.SHIELD, "§3§lWarden", "§7A resilient defensive class.", "§7(Abilities will be introduced later.)"));
        player.openInventory(inventory);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SelectionHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= 9) return;
        String selected = switch (event.getRawSlot()) { case 2 -> "vanguard"; case 4 -> "phantom"; case 6 -> "warden"; default -> null; };
        if (selected == null || !choose(player.getUniqueId(), selected, false)) return;
        player.closeInventory();
        player.sendMessage(plugin.message("&aYou selected " + selected.substring(0, 1).toUpperCase(Locale.ROOT) + selected.substring(1) + ". Your class is locked."));
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SelectionHolder) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SelectionHolder) || !(event.getPlayer() instanceof Player player)
                || get(player.getUniqueId()) != null) return;
        Bukkit.getScheduler().runTask(plugin, () -> open(player));
    }

    void save() {
        try { data.save(file); } catch (IOException ex) { plugin.getLogger().severe("Could not save player classes: " + ex.getMessage()); }
    }
    private ItemStack choice(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
        item.setItemMeta(meta);
        return item;
    }
    private static final class SelectionHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
