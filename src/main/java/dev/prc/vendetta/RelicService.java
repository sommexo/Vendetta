package dev.prc.vendetta;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Relic identity, validation, exploration discovery, and Marks-priced cleric offers. */
final class RelicService implements Listener {
    private final mark plugin;
    private final VanguardProgression progression;
    private final NamespacedKey classKey, abilityKey, tierKey, priceKey, shopKey;
    private final File exploredFile;
    private final YamlConfiguration exploredData;
    private final Set<String> explored = new HashSet<>();

    RelicService(mark plugin, VanguardProgression progression) {
        this.plugin = plugin;
        this.progression = progression;
        classKey = new NamespacedKey(plugin, "relic_class");
        abilityKey = new NamespacedKey(plugin, "relic_ability");
        tierKey = new NamespacedKey(plugin, "relic_tier");
        priceKey = new NamespacedKey(plugin, "relic_price");
        shopKey = new NamespacedKey(plugin, "relic_shop");
        exploredFile = new File(plugin.getDataFolder(), "explored-chests.yml");
        exploredData = YamlConfiguration.loadConfiguration(exploredFile);
        explored.addAll(exploredData.getStringList("opened"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelicUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() != null || event.getItem() == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(classKey, PersistentDataType.STRING)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!progression.get(player.getUniqueId()).vanguard) { player.sendMessage(plugin.message("&cChoose Vanguard before using its relics.")); return; }
        String ability = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        String clazz = meta.getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        int tier = meta.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        if (!"vanguard".equals(clazz)) { player.sendMessage(plugin.message("&cThis relic does not match your class.")); return; }
        VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
        VanguardProgression.Upgrade current = switch (ability) {
            case "berserker" -> data.berserker;
            case "headweb" -> data.headweb;
            case "ultimate" -> data.ultimate;
            default -> null;
        };
        if (current == null) { player.sendMessage(plugin.message("&cUnknown relic ability.")); return; }
        VanguardProgression.Upgrade next;
        if (tier == 1 || tier == 2) {
            if (current != VanguardProgression.Upgrade.DORMANT) { player.sendMessage(plugin.message("&cThat ability is already upgraded.")); return; }
            if ("ultimate".equals(ability) && tier == 1) next = VanguardProgression.Upgrade.IMPERFECT;
            else next = tier == 1 ? VanguardProgression.Upgrade.IMPERFECT : VanguardProgression.Upgrade.PERFECT;
        } else if (tier == 3) {
            if (!"ultimate".equals(ability)) { player.sendMessage(plugin.message("&cAscendant relics only upgrade the ultimate.")); return; }
            if (current != VanguardProgression.Upgrade.PERFECT) { player.sendMessage(plugin.message("&cUpgrade the ultimate with a Perfect Relic first.")); return; }
            if (!plugin.withdrawMarks(player.getUniqueId(), plugin.getConfig().getLong("relics.ascendant-mark-cost", 250))) {
                player.sendMessage(plugin.message("&cAscendant upgrade costs 250 Marks.")); return;
            }
            next = VanguardProgression.Upgrade.ASCENDANT;
        } else { player.sendMessage(plugin.message("&cInvalid relic tier.")); return; }

        switch (ability) {
            case "berserker" -> data.berserker = next;
            case "headweb" -> data.headweb = next;
            case "ultimate" -> data.ultimate = next;
        }
        progression.save(player.getUniqueId());
        event.getItem().setAmount(event.getItem().getAmount() - 1);
        player.sendMessage(plugin.message("&a" + capitalize(ability) + " upgraded to " + next + "!"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCleric(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager) || villager.getProfession() != Villager.Profession.CLERIC) return;
        event.setCancelled(true);
        Inventory inventory = Bukkit.createInventory(new RelicShopHolder(), 27, ChatColor.DARK_PURPLE + "Cleric Relics");
        RelicShopHolder holder = (RelicShopHolder) inventory.getHolder();
        holder.inventory = inventory;
        int level = villager.getVillagerLevel();
        for (int slot : new int[]{11, 13, 15}) {
            int tier = level >= 5 && slot == 15 ? 3 : offeredTier(Math.min(level, 4));
            String ability = tier == 3 ? "ultimate" : randomAbility();
            long price = plugin.getConfig().getLong("relics.prices.level-" + tier, tier == 1 ? 100 : tier == 2 ? 250 : 500);
            ItemStack item = createRelic(ability, tier);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(priceKey, PersistentDataType.LONG, price);
            meta.getPersistentDataContainer().set(shopKey, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GOLD + price + " Marks");
            lore.add(ChatColor.YELLOW + "Click to purchase");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
        event.getPlayer().openInventory(inventory);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RelicShopHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(shopKey, PersistentDataType.BYTE)) return;
        long price = meta.getPersistentDataContainer().getOrDefault(priceKey, PersistentDataType.LONG, Long.MAX_VALUE);
        if (!plugin.withdrawMarks(player.getUniqueId(), price)) { player.sendMessage(plugin.message("&cYou need " + price + " Marks for this relic.")); return; }
        ItemStack relic = item.clone();
        ItemMeta relicMeta = relic.getItemMeta();
        relicMeta.getPersistentDataContainer().remove(priceKey);
        relicMeta.getPersistentDataContainer().remove(shopKey);
        relicMeta.setLore(List.of(ChatColor.GRAY + "Class: Vanguard", ChatColor.GRAY + "Right-click to apply"));
        relic.setItemMeta(relicMeta);
        var overflow = player.getInventory().addItem(relic);
        overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        player.sendMessage(plugin.message("&aRelic purchased for " + price + " Marks."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onShopDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RelicShopHolder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplore(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof Container)) return;
        String location = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        if (!explored.add(location)) return;
        exploredData.set("opened", List.copyOf(explored));
        try { exploredData.save(exploredFile); } catch (IOException ex) { plugin.getLogger().warning("Could not save explored chest state: " + ex.getMessage()); }
        double chance = plugin.getConfig().getDouble("relics.exploration-drop-chance", 0.002);
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        int tier = ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("relics.exploration-perfect-chance", 0.05) ? 2 : 1;
        String ability = tier == 2 && ThreadLocalRandom.current().nextDouble() < 0.15 ? "ultimate" : randomAbility();
        ItemStack relic = createRelic(ability, tier);
        var overflow = event.getPlayer().getInventory().addItem(relic);
        overflow.values().forEach(stack -> event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), stack));
        event.getPlayer().sendMessage(plugin.message("&dYou discovered a Vanguard relic in the container!"));
    }

    ItemStack createRelic(String ability, int tier) {
        Material material = tier == 3 ? Material.NETHER_STAR : tier == 2 ? Material.AMETHYST_SHARD : Material.ECHO_SHARD;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        String title = tier == 3 ? "Ascendant" : tier == 2 ? "Perfect" : "Imperfect";
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + title + " Vanguard " + capitalize(ability) + " Relic");
        meta.setLore(List.of(ChatColor.GRAY + "Class: Vanguard", ChatColor.GRAY + "Ability: " + capitalize(ability), ChatColor.GRAY + "Tier: " + tier));
        meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, "vanguard");
        meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, ability.toLowerCase(Locale.ROOT));
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta);
        return item;
    }

    private int offeredTier(int villagerLevel) {
        if (villagerLevel >= 3 && ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("relics.cleric.level-2-chance", 0.35)) return 2;
        return 1;
    }
    private String randomAbility() { return switch (ThreadLocalRandom.current().nextInt(3)) { case 0 -> "berserker"; case 1 -> "headweb"; default -> "ultimate"; }; }
    private String capitalize(String text) { return Character.toUpperCase(text.charAt(0)) + text.substring(1); }
    private static final class RelicShopHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
