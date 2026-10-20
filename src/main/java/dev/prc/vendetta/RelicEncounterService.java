package dev.prc.vendetta;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hourly relic-villager encounters and persistent owner-bound structure quests.
 * All world mutations run on the server thread.
 */
final class RelicEncounterService implements Listener {
    private final mark plugin;
    private final RelicService relics;
    private final NamespacedKey villagerKey, questKey, shatterKey, shatterIdKey, shatterPaidKey, villagerQuestTypeKey, perfectOfferKey, punishmentWardenKey, uniqueHeartKey, waveKey;
    private final File stateFile;
    private final YamlConfiguration state;
    private final Map<UUID, Quest> quests = new HashMap<>();
    private final Map<UUID, HuntQuest> huntQuests = new HashMap<>();
    private final Map<UUID, Map<String, Long>> disabledRelics = new HashMap<>();
    private final Map<UUID, Long> angelMarkedUntil = new HashMap<>();
    private final Set<String> paidShatterIds = new HashSet<>();
    private final Set<String> usedShatterIds = new HashSet<>();
    private final Set<UUID> swordUseLocks = new HashSet<>();
    private final Set<UUID> punishmentDeathsProcessed = new HashSet<>();
    private final Set<String> claimedBoxes = new HashSet<>();
    private final Map<UUID, Set<UUID>> angelDamagers = new HashMap<>();
    private final Map<String, Integer> excludedTypes = new HashMap<>();
    private String currentWaveId = "";
    private long currentWaveExpiresAt;
    private int waveExpiryTask = -1;

    private record Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed, String heartId, long expiresAt) {
        Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed) {
            this(owner, world, x, y, z, type, claimed, "", 0L);
        }
        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private record HuntQuest(UUID owner, String mobType, int required, int progress, int diamonds, long marks) {
        HuntQuest progress(int next) { return new HuntQuest(owner, mobType, required, next, diamonds, marks); }
    }

    RelicEncounterService(mark plugin, RelicService relics) {
        this.plugin = plugin;
        this.relics = relics;
        villagerKey = new NamespacedKey(plugin, "relic_wave_villager");
        questKey = new NamespacedKey(plugin, "relic_quest_token");
        villagerQuestTypeKey = new NamespacedKey(plugin, "relic_villager_quest_type");
        perfectOfferKey = new NamespacedKey(plugin, "angel_perfect_offer");
        punishmentWardenKey = new NamespacedKey(plugin, "angel_punishment_warden");
        uniqueHeartKey = new NamespacedKey(plugin, "wanderer_unique_heart");
        waveKey = new NamespacedKey(plugin, "relic_wave_id");
        shatterKey = new NamespacedKey(plugin, "shatter_sword");
        shatterIdKey = new NamespacedKey(plugin, "shatter_id");
        shatterPaidKey = new NamespacedKey(plugin, "shatter_paid");
        stateFile = new File(plugin.getDataFolder(), "relic-encounters.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        paidShatterIds.addAll(state.getStringList("paid-shatter-swords"));
        usedShatterIds.addAll(state.getStringList("used-shatter-swords"));
        var exclusions = state.getConfigurationSection("excluded-angel-types");
        if (exclusions != null) for (String type : exclusions.getKeys(false)) {
            int waves = state.getInt("excluded-angel-types." + type, 0);
            if (waves > 0 && List.of("crazy", "imperfect", "perfect", "ascendant", "wanderer").contains(type)) excludedTypes.put(type, Math.min(3, waves));
        }
        claimedBoxes.addAll(state.getStringList("claimed-boxes"));
        for (String key : state.getConfigurationSection("quests") == null
                ? Collections.<String>emptySet() : state.getConfigurationSection("quests").getKeys(false)) {
            String path = "quests." + key;
            try {
                Quest quest = new Quest(UUID.fromString(state.getString(path + ".owner")),
                        state.getString(path + ".world"), state.getInt(path + ".x"), state.getInt(path + ".y"),
                        state.getInt(path + ".z"), state.getString(path + ".type", "nether"), state.getBoolean(path + ".claimed"),
                        state.getString(path + ".heart-id", ""), state.getLong(path + ".expires-at", 0L));
                quests.put(quest.owner(), quest);
            } catch (Exception ex) {
                plugin.getLogger().warning("Ignoring malformed relic quest entry " + key);
            }
        }
        for (String key : state.getConfigurationSection("hunt-quests") == null
                ? Collections.<String>emptySet() : state.getConfigurationSection("hunt-quests").getKeys(false)) {
            String path = "hunt-quests." + key;
            try {
                UUID owner = UUID.fromString(key);
                huntQuests.put(owner, new HuntQuest(owner, state.getString(path + ".mob", "ZOMBIE"),
                        state.getInt(path + ".required", 5), state.getInt(path + ".progress", 0),
                        state.getInt(path + ".diamonds", 3), state.getLong(path + ".marks", 0)));
            } catch (Exception ex) {
                plugin.getLogger().warning("Ignoring malformed hunt quest entry " + key);
            }
        }
        // Shatter Sword disables are intentionally cleared on restart; an old timer must never strand a relic.
        state.set("disabled", null);
    }

    void start() {
        // Start a wave on startup, then repeat at the configured interval (default one hour).
        expireWandererQuests();
        cleanupOldVillagers();
        long interval = Math.max(1, plugin.getConfig().getLong("relic-villagers.interval-minutes", 60)) * 60L * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnWave, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnWave, interval, interval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireOldDisables, 20L * 30, 20L * 30);
    }

    void shutdown() {
        expireWandererQuests();
        cleanupOldVillagers();
        currentWaveId = "";
        currentWaveExpiresAt = 0L;
        saveState();
    }

    void triggerManualWave() { spawnWave(); }

    private void spawnWave() {
        if (currentWaveExpiresAt > 0L) {
            currentWaveExpiresAt = 0L;
            expireWandererQuests();
        }
        cleanupOldVillagers();
        int count = 8;
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
        Set<String> excludedThisWave = new HashSet<>();
        excludedTypes.forEach((type, waves) -> { if (waves > 0) excludedThisWave.add(type); });
        List<String> eligibleTypes = new ArrayList<>(List.of("crazy", "imperfect", "perfect", "ascendant", "wanderer"));
        eligibleTypes.removeAll(excludedThisWave);
        if (eligibleTypes.isEmpty()) eligibleTypes.add("imperfect");
        List<String> waveTypes = new ArrayList<>(eligibleTypes);
        while (waveTypes.size() < count) waveTypes.add(eligibleTypes.get(ThreadLocalRandom.current().nextInt(eligibleTypes.size())));
        Collections.shuffle(waveTypes);
        List<Integer> imperfectSlots = new ArrayList<>();
        for (int i = 0; i < waveTypes.size(); i++) if (waveTypes.get(i).equals("imperfect")) imperfectSlots.add(i);
        Collections.shuffle(imperfectSlots);
        // Nearest whole Angel count to 20%; ties round down (e.g. 2 Imperfect Angels => 0 bonus offers).
        int bonusPerfectOffers = (int) Math.floor(imperfectSlots.size() * 0.20 + 0.5);
        Set<Integer> bonusSlots = new HashSet<>(imperfectSlots.subList(0, Math.min(bonusPerfectOffers, imperfectSlots.size())));
        String waveId = UUID.randomUUID().toString();
        currentWaveId = waveId;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            SpawnPoint point = findStructureLocation(worlds);
            if (point == null) continue;
            Location location = point.location();
            Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
            villager.setCustomName(ChatColor.GOLD + "Angel");
            villager.setCustomNameVisible(true);
            villager.setProfession(Villager.Profession.LIBRARIAN);
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.setVillagerLevel(5);
            villager.setVillagerExperience(0);
            villager.setAdult();
            villager.setAI(false);
            villager.setCollidable(false);
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.getPersistentDataContainer().set(villagerKey, PersistentDataType.BYTE, (byte) 1);
            villager.getPersistentDataContainer().set(waveKey, PersistentDataType.STRING, waveId);
            String questType = waveTypes.get(i);
            villager.getPersistentDataContainer().set(villagerQuestTypeKey, PersistentDataType.STRING, questType);
            boolean bonusPerfect = questType.equals("imperfect") && bonusSlots.contains(i);
            villager.getPersistentDataContainer().set(perfectOfferKey, PersistentDataType.BYTE, (byte) (bonusPerfect ? 1 : 0));
            if (List.of("imperfect", "perfect", "ascendant", "wanderer").contains(questType)) {
                villager.setRecipes(createTrades(point.structureName(), questType, bonusPerfect));
            } else {
                villager.setRecipes(Collections.emptyList());
            }
            spawned++;
        }
        excludedTypes.replaceAll((type, waves) -> waves - 1);
        excludedTypes.entrySet().removeIf(entry -> entry.getValue() <= 0);
        saveExclusions();
        if (spawned > 0) {
            Bukkit.broadcastMessage(plugin.message("Angels escaped"));
            long lifetime = 15L * 60 * 20;
            currentWaveExpiresAt = System.currentTimeMillis() + 15L * 60 * 1000;
            waveExpiryTask = Bukkit.getScheduler().runTaskLater(plugin, () -> expireWave(waveId), lifetime).getTaskId();
        }
        plugin.getLogger().info("Spawned " + spawned + " relic villagers for this hourly wave.");
    }

    private void expireWave(String waveId) {
        if (!waveId.equals(currentWaveId)) return;
        expireWandererQuests();
        cleanupOldVillagers();
        Bukkit.broadcastMessage(plugin.message("Escaped angels enchained"));
        currentWaveExpiresAt = 0L;
        currentWaveId = "";
        waveExpiryTask = -1;
    }

    private void saveExclusions() {
        state.set("excluded-angel-types", null);
        excludedTypes.forEach((type, waves) -> state.set("excluded-angel-types." + type, waves));
        saveState();
    }

    private void cleanupOldVillagers() {
        if (waveExpiryTask != -1) Bukkit.getScheduler().cancelTask(waveExpiryTask);
        waveExpiryTask = -1;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Villager villager
                        && villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) {
                    villager.remove();
                }
            }
        }
        angelDamagers.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Villager villager)
                    || !villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) continue;
            String id = villager.getPersistentDataContainer().getOrDefault(waveKey, PersistentDataType.STRING, "");
            if (!id.equals(currentWaveId)) villager.remove();
        }
    }

    private record SpawnPoint(Location location, String structureName) { }

    private SpawnPoint findStructureLocation(List<World> worlds) {
        List<StructureWeight> structures = configuredStructures();
        if (structures.isEmpty()) return null;
        for (int attempt = 0; attempt < 18; attempt++) {
            World world = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
            Player anchor = world.getPlayers().isEmpty() ? null : world.getPlayers().get(ThreadLocalRandom.current().nextInt(world.getPlayers().size()));
            Location origin = anchor == null ? world.getSpawnLocation() : anchor.getLocation();
            StructureWeight choice = weightedChoice(structures);
            try {
                var result = world.locateNearestStructure(origin, choice.structure(), Math.max(1, plugin.getConfig().getInt("relic-villagers.search-radius-chunks", 96)), false);
                if (result == null) continue;
                Location loc = result.getLocation().clone().add(2, 1, 2);
                if (loc.getBlock().getType().isSolid()) loc.add(0, 1, 0);
                if (loc.getBlock().isPassable() && loc.clone().add(0, 1, 0).getBlock().isPassable()) {
                    loc.getChunk().load();
                    return new SpawnPoint(loc, choice.name());
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().fine("Structure unavailable on this server version: " + choice.name());
            }
        }
        // Last-resort safe spawn so a wave is not silently lost when no configured structure is discoverable.
        World world = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
        Location spawn = world.getSpawnLocation().clone().add(3, 0, 3);
        spawn.setY(world.getHighestBlockYAt(spawn) + 1);
        return spawn.getBlock().isPassable() ? new SpawnPoint(spawn, "VILLAGE_PLAINS") : null;
    }

    private record StructureWeight(String name, org.bukkit.generator.structure.Structure structure, int weight) { }

    private List<StructureWeight> configuredStructures() {
        List<StructureWeight> list = new ArrayList<>();
        var section = plugin.getConfig().getConfigurationSection("relic-villagers.structures");
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            int weight = Math.max(0, section.getInt(key + ".weight", 1));
            if (weight == 0) continue;
            try {
                NamespacedKey structureKey = NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT));
                var structure = Registry.STRUCTURE.get(structureKey);
                if (structure == null) throw new IllegalArgumentException("Unknown structure key " + structureKey);
                list.add(new StructureWeight(key, structure, weight));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown configured relic villager structure: " + key);
            }
        }
        return list;
    }

    private StructureWeight weightedChoice(List<StructureWeight> structures) {
        int total = structures.stream().mapToInt(StructureWeight::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (StructureWeight structure : structures) {
            roll -= structure.weight();
            if (roll < 0) return structure;
        }
        return structures.get(structures.size() - 1);
    }

    private List<MerchantRecipe> createTrades(String structureName, String angelType, boolean bonusPerfect) {
        List<MerchantRecipe> recipes = new ArrayList<>();
        // Generated once per villager; reopening the merchant screen does not reroll offers.
        List<Integer> tiers = switch (angelType) {
            case "imperfect" -> bonusPerfect ? List.of(1, 2) : List.of(1);
            case "perfect" -> Collections.nCopies(ThreadLocalRandom.current().nextInt(2, 5), 2);
            case "ascendant" -> List.of(1, 2, 3);
            default -> List.of();
        };
        String[] classes = {"vanguard", "phantom", "warden"};
        for (int tier : tiers) {
            String clazz = classes[ThreadLocalRandom.current().nextInt(classes.length)];
            String ability = relicAbility(clazz, tier);
            ItemStack output = relics.createRelic(clazz, ability, tier);
            MerchantRecipe recipe = new MerchantRecipe(output, 0, 999_999, true);
            recipe.addIngredient(new ItemStack(Material.EMERALD, tier == 1 ? 16 : 32));
            recipe.addIngredient(new ItemStack(Material.DIAMOND, tier == 1 ? 2 : 8));
            recipes.add(recipe);
        }
        if (angelType.equals("ascendant")) {
            ItemStack sword = createShatterSword();
            MerchantRecipe swordRecipe = new MerchantRecipe(sword, 0, 999_999, true);
            swordRecipe.addIngredient(new ItemStack(Material.DIAMOND, 16));
            swordRecipe.addIngredient(new ItemStack(Material.NETHERITE_INGOT, 1));
            recipes.add(swordRecipe);
        }
        return recipes;
    }

    private int chooseTradeTier(String structureName) {
        String key = structureName.toUpperCase(Locale.ROOT);
        double roll = ThreadLocalRandom.current().nextDouble();
        if (key.startsWith("END_CITY")) return roll < 0.30 ? 3 : roll < 0.80 ? 2 : 1;
        if (key.startsWith("RUINED_PORTAL")) return roll < 0.15 ? 3 : roll < 0.65 ? 2 : 1;
        if (key.startsWith("OCEAN_MONUMENT")) return roll < 0.10 ? 3 : roll < 0.55 ? 2 : 1;
        if (key.startsWith("DESERT_PYRAMID")) return roll < 0.35 ? 2 : 1;
        if (key.startsWith("PILLAGER_OUTPOST")) return roll < 0.20 ? 2 : 1;
        if (key.startsWith("VILLAGE")) return roll < 0.15 ? 2 : 1;
        return roll < 0.20 ? 2 : 1;
    }

    private ItemStack createShatterSword() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "Shatter Sword");
        String identity = UUID.randomUUID().toString();
        meta.setLore(List.of(ChatColor.GRAY + "Prepaid on first acquisition (500 Marks)",
                ChatColor.GRAY + "Disables one highest-tier eligible relic for 30 minutes"));
        meta.getPersistentDataContainer().set(shatterKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(shatterIdKey, PersistentDataType.STRING, identity);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) return;
        String type = villager.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "crazy");
        if (type.equals("crazy")) event.getPlayer().sendMessage(plugin.message("&cCrazy Angel: left-click to receive a persistent quest."));
        else if (type.equals("wanderer")) {
            event.setCancelled(true);
            if (submitWandererHeart(event.getPlayer())) return;
            event.getPlayer().sendMessage(plugin.message("&dWanderer Angel: left-click to start a cache quest; submit its unique Heart here."));
        }
        else event.getPlayer().sendMessage(plugin.message("&e" + capitalize(type) + " Angel: left-click for details; right-click to trade."));
        if (type.equals("crazy")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVillagerLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) return;
        String type = villager.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "crazy");
        if (type.equals("crazy")) startCrazyQuest(player);
        else if (type.equals("wanderer")) startWandererQuest(player);
        else player.sendMessage(plugin.message("&e" + capitalize(type) + " Angel: right-click to trade."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onAngelDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager angel)
                || !angel.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) return;
        Player player = playerDamager(event);
        if (player != null) angelDamagers.computeIfAbsent(angel.getUniqueId(), ignored -> new HashSet<>()).add(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onShatterSwordPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (!isShatterSword(item)) return;
        if (!payForShatterSword(player, item)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShatterSwordDrag(InventoryDragEvent event) {
        if (isShatterSword(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShatterSwordContainerTake(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getType() == InventoryType.MERCHANT) {
            purchaseShatterSwordFromMerchant(event, player);
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory().equals(player.getInventory())) return;
        ItemStack source = event.getCurrentItem();
        if (!isShatterSword(source)) return;
        event.setCancelled(true);
        if (!payForShatterSword(player, source)) return;
        ItemStack acquired = source.clone();
        acquired.setAmount(1);
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(acquired);
        if (!overflow.isEmpty()) {
            // A failed delivery must not charge. Restore the source sword and roll back payment.
            source.setItemMeta(event.getCurrentItem().getItemMeta());
            refundShatterAcquisition(player, source);
            return;
        }
        if (source.getAmount() <= 1) event.setCurrentItem(null);
        else source.setAmount(source.getAmount() - 1);
    }

    private void purchaseShatterSwordFromMerchant(InventoryClickEvent event, Player player) {
        if (event.getRawSlot() != 2 || !(event.getView().getTopInventory() instanceof MerchantInventory merchant)) return;
        ItemStack output = merchant.getItem(2);
        if (!isShatterSword(output)) return;
        event.setCancelled(true);
        MerchantRecipe recipe = merchant.getSelectedRecipe();
        if (recipe == null || player.getInventory().firstEmpty() < 0) return;
        List<ItemStack> required = recipe.getIngredients();
        for (int i = 0; i < required.size() && i < 2; i++) {
            ItemStack have = merchant.getItem(i);
            ItemStack need = required.get(i);
            if (have == null || have.getType() != need.getType() || have.getAmount() < need.getAmount()) return;
        }
        if (!plugin.debit(player.getUniqueId(), 500)) {
            player.sendMessage(plugin.message("&cA Shatter Sword costs 500 Marks on acquisition."));
            return;
        }
        for (int i = 0; i < required.size() && i < 2; i++) {
            ItemStack have = merchant.getItem(i);
            have.setAmount(have.getAmount() - required.get(i).getAmount());
            merchant.setItem(i, have.getAmount() == 0 ? null : have);
        }
        ItemStack delivered = output.clone();
        ItemMeta meta = delivered.getItemMeta();
        String id = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(shatterIdKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(shatterPaidKey, PersistentDataType.BYTE, (byte) 1);
        delivered.setItemMeta(meta);
        paidShatterIds.add(id);
        if (!saveState()) {
            paidShatterIds.remove(id);
            plugin.deposit(player.getUniqueId(), 500);
            for (int i = 0; i < required.size() && i < 2; i++) {
                ItemStack have = merchant.getItem(i);
                ItemStack need = required.get(i);
                if (have == null) merchant.setItem(i, need.clone());
                else if (have.getType() == need.getType()) have.setAmount(have.getAmount() + need.getAmount());
            }
            player.sendMessage(plugin.message("&cThe Shatter Sword payment could not be saved; the trade was rolled back."));
            return;
        }
        player.getInventory().addItem(delivered);
        player.updateInventory();
    }

    private boolean isShatterSword(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(shatterKey, PersistentDataType.BYTE);
    }

    private boolean payForShatterSword(Player player, ItemStack item) {
        if (!isShatterSword(item)) return true;
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        String id = pdc.get(shatterIdKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) return false;
        if (paidShatterIds.contains(id)) {
            pdc.set(shatterPaidKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
            return true;
        }
        if (!plugin.debit(player.getUniqueId(), 500)) {
            player.sendMessage(plugin.message("&cA Shatter Sword costs 500 Marks on first acquisition."));
            return false;
        }
        paidShatterIds.add(id);
        pdc.set(shatterPaidKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        if (!saveState()) {
            paidShatterIds.remove(id);
            plugin.deposit(player.getUniqueId(), 500);
            pdc.remove(shatterPaidKey);
            item.setItemMeta(meta);
            return false;
        }
        plugin.audit("Shatter Sword prepaid acquisition id=" + id + " player=" + player.getUniqueId());
        return true;
    }

    private void refundShatterAcquisition(Player player, ItemStack item) {
        String id = item.getItemMeta().getPersistentDataContainer().get(shatterIdKey, PersistentDataType.STRING);
        if (id != null && paidShatterIds.remove(id)) {
            plugin.deposit(player.getUniqueId(), 500);
            item.getItemMeta().getPersistentDataContainer().remove(shatterPaidKey);
            saveState();
        }
    }

    @EventHandler
    public void onPunishmentWardenKill(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager angel
                && angel.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) {
            Set<UUID> attackers = angelDamagers.remove(angel.getUniqueId());
            String type = angel.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "imperfect");
            excludedTypes.put(type, 3);
            saveExclusions();
            if (attackers != null) for (UUID attacker : attackers) {
                if (plugin.deductUpTo(attacker, 1_000)) plugin.audit("Angel kill penalty 1000 Marks target=" + attacker + " angel=" + angel.getUniqueId());
            }
            Player killer = playerDamager(angel.getLastDamageCause() instanceof EntityDamageByEntityEvent hit ? hit : null);
            if (killer == null) killer = angel.getKiller();
            if (killer != null) {
                long until = System.currentTimeMillis() + 30L * 60 * 1000;
                angelMarkedUntil.merge(killer.getUniqueId(), until, Math::max);
                Location spawnAt = killer.getLocation();
                for (int i = 0; i < 3; i++) {
                    Location point = spawnAt.clone().add((i - 1) * 2.0, 0, 1.5);
                    Warden warden = (Warden) spawnAt.getWorld().spawnEntity(point, EntityType.WARDEN);
                    warden.getPersistentDataContainer().set(punishmentWardenKey, PersistentDataType.BYTE, (byte) 1);
                    warden.setTarget(killer);
                    warden.setPersistent(true);
                    warden.setRemoveWhenFarAway(false);
                }
                killer.sendMessage(plugin.message("&cThe Angels mark you. Three Wardens are hunting you."));
            }
            saveState();
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!punishmentDeathsProcessed.add(victim.getUniqueId())) return;
        EntityDamageByEntityEvent lastHit = victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hit ? hit : null;
        if (lastHit == null || !(lastHit.getDamager() instanceof Warden warden)
                || !warden.getPersistentDataContainer().has(punishmentWardenKey, PersistentDataType.BYTE)) return;
        int removed = relics.removeGenuineRelics(victim);
        victim.sendMessage(plugin.message("&4A punishment Warden destroyed " + removed + " genuine relic item(s) and all applied relic tiers."));
        plugin.audit("Tracked Angel punishment Warden deleted " + removed + " carried genuine relics and applied relic tiers from " + victim.getUniqueId());
    }

    @EventHandler
    public void clearPunishmentDeathGuard(PlayerRespawnEvent event) {
        punishmentDeathsProcessed.remove(event.getPlayer().getUniqueId());
    }

    private Player playerDamager(EntityDamageByEntityEvent event) {
        if (event == null) return null;
        Entity damager = event.getDamager();
        if (damager instanceof Player player) return player;
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    private List<ItemStack> createWandererCacheContents(Player owner, String heartId) {
        List<ItemStack> contents = new ArrayList<>();
        ItemStack heart = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = heart.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Wanderer's Heart");
        meta.setLore(List.of(ChatColor.GRAY + "Unique quest item. Return it to the Wanderer."));
        meta.getPersistentDataContainer().set(uniqueHeartKey, PersistentDataType.STRING, heartId);
        heart.setItemMeta(meta);
        contents.add(heart);
        String clazz = plugin.selectedClass(owner.getUniqueId());
        if (!List.of("vanguard", "phantom", "warden").contains(clazz)) clazz = randomClass();
        contents.add(relics.createRelic(clazz, relicAbility(clazz, 2), 2));
        contents.add(randomReward("wanderer"));
        contents.add(randomReward("wanderer"));
        if (ThreadLocalRandom.current().nextDouble() < 0.20) {
            String ascendantClass = randomClass();
            contents.add(relics.createRelic(ascendantClass, relicAbility(ascendantClass, 3), 3));
        }
        return contents;
    }

    private boolean submitWandererHeart(Player player) {
        Quest quest = quests.get(player.getUniqueId());
        if (quest == null || quest.claimed() || !quest.type().equals("wanderer")) return false;
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean mainHand = hasQuestHeart(hand, quest.heartId());
        if (!mainHand) hand = player.getInventory().getItemInOffHand();
        if (!hasQuestHeart(hand, quest.heartId())) return false;
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else if (mainHand) player.getInventory().setItemInMainHand(null);
        else player.getInventory().setItemInOffHand(null);
        Quest completed = new Quest(quest.owner(), quest.world(), quest.x(), quest.y(), quest.z(), quest.type(), true, quest.heartId(), quest.expiresAt());
        quests.put(quest.owner(), completed);
        saveQuest(completed);
        removeQuestBlock(quest);
        long reward = ThreadLocalRandom.current().nextLong(400, 501);
        plugin.credit(player.getUniqueId(), reward, "Wanderer cache quest reward");
        player.sendMessage(plugin.message("&aWanderer quest complete. You received " + reward + " Marks."));
        return true;
    }

    private boolean hasQuestHeart(ItemStack item, String heartId) {
        return item != null && item.getType() == Material.HEART_OF_THE_SEA && item.hasItemMeta()
                && heartId != null && !heartId.isBlank()
                && heartId.equals(item.getItemMeta().getPersistentDataContainer().get(uniqueHeartKey, PersistentDataType.STRING));
    }

    private void expireWandererQuests() {
        for (Quest quest : new ArrayList<>(quests.values())) {
            if (quest.claimed() || !quest.type().equals("wanderer")) continue;
            Quest failed = new Quest(quest.owner(), quest.world(), quest.x(), quest.y(), quest.z(), quest.type(), true, quest.heartId(), quest.expiresAt());
            quests.put(quest.owner(), failed);
            saveQuest(failed);
            removeQuestBlock(quest);
            plugin.applyWandererFailurePenalty(quest.owner(), 500, quest.heartId().isBlank() ? quest.owner() + ":" + quest.key() : quest.heartId());
            Player player = Bukkit.getPlayer(quest.owner());
            if (player != null) player.sendMessage(plugin.message("&cYour Wanderer quest expired. 500 Marks were deducted."));
        }
    }

    private void removeQuestBlock(Quest quest) {
        World world = Bukkit.getWorld(quest.world());
        if (world == null) return;
        world.getChunkAt(quest.x() >> 4, quest.z() >> 4).load();
        Block block = world.getBlockAt(quest.x(), quest.y(), quest.z());
        if (block.getType() != Material.SHULKER_BOX || !(block.getState() instanceof org.bukkit.block.ShulkerBox box)) return;
        String cacheId = box.getPersistentDataContainer().get(questKey, PersistentDataType.STRING);
        if (quest.heartId().equals(cacheId)) block.setType(Material.AIR, false);
    }

    private boolean hasActiveQuest(UUID owner) {
        Quest cache = quests.get(owner);
        return (cache != null && !cache.claimed()) || huntQuests.containsKey(owner);
    }

    private void startCrazyQuest(Player player) {
        if (hasActiveQuest(player.getUniqueId())) {
            player.sendMessage(plugin.message("&cFinish your current angelic quest first."));
            return;
        }
        int roll = ThreadLocalRandom.current().nextInt(4);
        if (roll == 0) {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "WARDEN", 1, 0, 8, ThreadLocalRandom.current().nextLong(500, 1251));
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat a Warden. Reward: 8 diamonds and a Marks bonus."));
        } else if (roll == 1) {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "CREEPER", 5, 0, 5, ThreadLocalRandom.current().nextLong(500, 1251));
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 5 Creepers. Reward: diamonds and Marks."));
        } else if (roll == 2) {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "SKELETON", 8, 0, 4, 0);
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 8 Skeletons. Reward: a custom Angel Fragment."));
        } else {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "PILLAGER", 6, 0, 6, ThreadLocalRandom.current().nextLong(500, 1251));
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 6 Pillagers. Reward: diamonds and Marks."));
        }
        // Crazy quests are independent of the Angel wave and persist until completion.
    }

    private void startWandererQuest(Player player) {
        Quest existing = quests.get(player.getUniqueId());
        if (existing != null && !existing.claimed() && existing.type().equals("wanderer")) {
            player.sendMessage(plugin.message("&cYou already have an active Wanderer cache quest."));
            return;
        }
        if (hasActiveQuest(player.getUniqueId())) { player.sendMessage(plugin.message("&cFinish your current angelic quest first.")); return; }
        String selectedClass = plugin.selectedClass(player.getUniqueId());
        if (selectedClass == null || !List.of("vanguard", "phantom", "warden").contains(selectedClass)) {
            player.sendMessage(plugin.message("&cChoose a class before accepting a Wanderer cache quest."));
            return;
        }
        if (currentWaveExpiresAt <= System.currentTimeMillis()) { player.sendMessage(plugin.message("&cThis Angel wave is ending; try again in the next wave.")); return; }
        World world = player.getWorld();
        Location origin = player.getLocation();
        Location chosen = null;
        // Prefer a cave-adjacent safe position; every attempt stays within 100 blocks of the accepting player.
        for (int i = 0; i < 80; i++) {
            int dx = ThreadLocalRandom.current().nextInt(-100, 101);
            int dz = ThreadLocalRandom.current().nextInt(-100, 101);
            if ((dx * dx) + (dz * dz) > 10000) continue;
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z) - ThreadLocalRandom.current().nextInt(2, 12);
            y = Math.max(world.getMinHeight() + 2, y);
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                    && candidate.clone().subtract(0, 1, 0).getBlock().isSolid()) { chosen = candidate; break; }
        }
        if (chosen == null) {
            int x = origin.getBlockX() + ThreadLocalRandom.current().nextInt(-60, 61);
            int z = origin.getBlockZ() + ThreadLocalRandom.current().nextInt(-60, 61);
            chosen = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
        }
        chosen.getChunk().load();
        chosen.getBlock().setType(Material.SHULKER_BOX, false);
        Location cacheLocation = chosen;
        String heartId = UUID.randomUUID().toString();
        long expiresAt = currentWaveExpiresAt;
        Quest quest = new Quest(player.getUniqueId(), world.getName(), chosen.getBlockX(), chosen.getBlockY(), chosen.getBlockZ(), "wanderer", false, heartId, expiresAt);
        quests.put(player.getUniqueId(), quest);
        if (chosen.getBlock().getState() instanceof org.bukkit.block.ShulkerBox box) {
            List<ItemStack> contents = createWandererCacheContents(player, heartId);
            for (ItemStack item : contents) {
                Map<Integer, ItemStack> overflow = box.getInventory().addItem(item);
                for (ItemStack stack : overflow.values()) world.dropItemNaturally(cacheLocation, stack);
            }
            box.getPersistentDataContainer().set(questKey, PersistentDataType.STRING, heartId);
            box.update(true, false);
        }
        saveQuest(quest);
        player.sendMessage(plugin.message("&dWanderer quest accepted! A cache spawned within 100 blocks. Retrieve its unique Heart of the Sea and submit it at the Wanderer. Cache at &f"
                + quest.x() + ", " + quest.y() + ", " + quest.z() + " &7(" + quest.world() + ")"));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
    }

    private void startHuntQuest(Player player) {
        if (hasActiveQuest(player.getUniqueId())) {
            player.sendMessage(plugin.message("&cFinish your current angelic quest first."));
            return;
        }
        List<String> mobs = List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "PILLAGER");
        String mob = mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
        int required = ThreadLocalRandom.current().nextInt(5, 11);
        int diamonds = ThreadLocalRandom.current().nextInt(3, 9);
        long marks = ThreadLocalRandom.current().nextDouble() < 0.30 ? ThreadLocalRandom.current().nextLong(500, 1251) : 0;
        HuntQuest quest = new HuntQuest(player.getUniqueId(), mob, required, 0, diamonds, marks);
        huntQuests.put(player.getUniqueId(), quest);
        saveHuntQuest(quest);
        player.sendMessage(plugin.message("&dAngelic hunt accepted: defeat &f" + required + " " + mob.toLowerCase(Locale.ROOT)
                + "s&d. Reward: &b" + diamonds + " diamonds" + (marks > 0 ? " &dand &e" + marks + " Marks&d." : "&d.")));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
    }

    private void startCacheQuest(Player player, String type) {
        if (hasActiveQuest(player.getUniqueId())) {
            player.sendMessage(plugin.message("&cFinish your current angelic quest first."));
            return;
        }
        World.Environment env = type.equals("nether") ? World.Environment.NETHER : World.Environment.THE_END;
        List<World> candidates = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == env).toList();
        if (candidates.isEmpty()) {
            player.sendMessage(plugin.message("&cThe required dimension is not available on this server."));
            return;
        }
        World world = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location base = world.getSpawnLocation().clone();
        base.add(ThreadLocalRandom.current().nextInt(-600, 601), 0, ThreadLocalRandom.current().nextInt(-600, 601));
        base.setY(Math.max(world.getMinHeight() + 5, Math.min(world.getMaxHeight() - 5, world.getHighestBlockYAt(base) + 1)));
        base.getChunk().load();
        base.getBlock().setType(Material.SHULKER_BOX, false);
        Quest quest = new Quest(player.getUniqueId(), world.getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ(), type, false);
        quests.put(player.getUniqueId(), quest);
        saveQuest(quest);
        player.sendMessage(plugin.message("&d" + type.toUpperCase(Locale.ROOT) + " expedition accepted. Find your shulker cache at &f"
                + quest.x() + ", " + quest.y() + ", " + quest.z() + " &din &f" + quest.world() + "&d. Only you can claim it."));
        player.sendMessage(plugin.message("&7The cache contains three rewards and is saved across restarts."));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHuntMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        HuntQuest quest = huntQuests.get(killer.getUniqueId());
        if (quest == null || !event.getEntity().getType().name().equals(quest.mobType())) return;
        int progress = quest.progress() + 1;
        if (progress < quest.required()) {
            HuntQuest updated = quest.progress(progress);
            huntQuests.put(killer.getUniqueId(), updated);
            saveHuntQuest(updated);
            killer.sendMessage(plugin.message("&dAngelic hunt: &f" + progress + "/" + quest.required() + " " + quest.mobType().toLowerCase(Locale.ROOT) + "s"));
            return;
        }
        huntQuests.remove(killer.getUniqueId());
        state.set("hunt-quests." + killer.getUniqueId(), null);
        saveState();
        Map<Integer, ItemStack> overflow = killer.getInventory().addItem(new ItemStack(Material.DIAMOND, quest.diamonds()));
        overflow.values().forEach(item -> killer.getWorld().dropItemNaturally(killer.getLocation(), item));
        if (quest.mobType().equals("SKELETON")) {
            ItemStack fragment = new ItemStack(Material.AMETHYST_SHARD);
            ItemMeta fragmentMeta = fragment.getItemMeta();
            fragmentMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Angel Fragment");
            fragmentMeta.setLore(List.of(ChatColor.GRAY + "A mysterious relic-crafting material."));
            fragment.setItemMeta(fragmentMeta);
            Map<Integer, ItemStack> fragmentOverflow = killer.getInventory().addItem(fragment);
            fragmentOverflow.values().forEach(item -> killer.getWorld().dropItemNaturally(killer.getLocation(), item));
        }
        if (quest.marks() > 0) plugin.credit(killer.getUniqueId(), quest.marks(), "angelic hunt quest reward");
        killer.sendMessage(plugin.message("&aAngelic hunt complete! You received " + quest.diamonds() + " diamonds"
                + (quest.marks() > 0 ? " and " + quest.marks() + " Marks." : "!")));
        killer.playSound(killer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
    }

    private void saveHuntQuest(HuntQuest quest) {
        String path = "hunt-quests." + quest.owner();
        state.set(path + ".mob", quest.mobType());
        state.set(path + ".required", quest.required());
        state.set(path + ".progress", quest.progress());
        state.set(path + ".diamonds", quest.diamonds());
        state.set(path + ".marks", quest.marks());
        saveState();
    }

    private void saveQuest(Quest quest) {
        String path = "quests." + quest.owner();
        state.set(path + ".owner", quest.owner().toString());
        state.set(path + ".world", quest.world());
        state.set(path + ".x", quest.x());
        state.set(path + ".y", quest.y());
        state.set(path + ".z", quest.z());
        state.set(path + ".type", quest.type());
        state.set(path + ".claimed", quest.claimed());
        state.set(path + ".heart-id", quest.heartId());
        state.set(path + ".expires-at", quest.expiresAt());
        saveState();
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuestBoxBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        Quest quest = quests.values().stream().filter(q -> q.key().equals(key) && !q.claimed()).findFirst().orElse(null);
        if (quest == null) return;
        if (quest.type().equals("wanderer")) return;
        Player player = event.getPlayer();
        if (!quest.owner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("&cThis angelic cache belongs to another player."));
            return;
        }
        event.setCancelled(true);
        if (!claimedBoxes.add(key)) {
            player.sendMessage(plugin.message("&cThis cache has already been claimed."));
            return;
        }
        event.setDropItems(false);
        block.setType(Material.AIR, false);
        Quest completed = new Quest(quest.owner(), quest.world(), quest.x(), quest.y(), quest.z(), quest.type(), true);
        quests.put(quest.owner(), completed);
        saveQuest(completed);
        deliverRewards(player, quest.type());
        player.sendMessage(plugin.message("&dThe angelic cache yields three rewards."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isQuestCache(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isQuestCache(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    private boolean isQuestCache(String world, int x, int y, int z) {
        String key = world + ":" + x + ":" + y + ":" + z;
        return quests.values().stream().anyMatch(quest -> !quest.claimed() && !quest.type().equals("wanderer") && quest.key().equals(key));
    }

    private void deliverRewards(Player player, String type) {
        List<ItemStack> rewards = new ArrayList<>();
        // One guaranteed relic and two additional independent rolls.
        int guaranteedTier = ThreadLocalRandom.current().nextDouble() < 0.20 ? 3 : 2;
        String guaranteedClass = plugin.selectedClass(player.getUniqueId());
        if (!List.of("vanguard", "phantom", "warden").contains(guaranteedClass)) guaranteedClass = randomClass();
        rewards.add(relics.createRelic(guaranteedClass, relicAbility(guaranteedClass, guaranteedTier), guaranteedTier));
        rewards.add(randomReward(type));
        rewards.add(randomReward(type));
        for (ItemStack reward : rewards) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(reward);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private String relicAbility(String clazz, int tier) {
        if ("vanguard".equals(clazz)) return tier == 3 ? "ultimate" : "select";
        return "class_relic";
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) return "Angel";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private String randomClass() {
        return List.of("vanguard", "phantom", "warden").get(ThreadLocalRandom.current().nextInt(3));
    }

    private ItemStack randomReward(String type) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 22) { String clazz = randomClass(); return relics.createRelic(clazz, relicAbility(clazz, 2), 2); }
        if (roll < 30) { String clazz = randomClass(); return relics.createRelic(clazz, relicAbility(clazz, 3), 3); }
        if (roll < 38) return createShatterSword();
        if (roll < 45) {
            ItemStack custom = new ItemStack(Material.AMETHYST_SHARD);
            ItemMeta meta = custom.getItemMeta();
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Angel Fragment");
            meta.setLore(List.of(ChatColor.GRAY + "A mysterious relic-crafting material."));
            custom.setItemMeta(meta);
            return custom;
        }
        if (roll < 65) return new ItemStack(Material.DIAMOND, ThreadLocalRandom.current().nextInt(2, 9));
        if (roll < 82) return new ItemStack(Material.GOLD_INGOT, ThreadLocalRandom.current().nextInt(8, 25));
        if (roll < 88) return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        if (roll < 93) return new ItemStack(Material.GOLDEN_APPLE, ThreadLocalRandom.current().nextInt(1, 4));
        if (roll < 97) return new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        return new ItemStack(Material.ANCIENT_DEBRIS, ThreadLocalRandom.current().nextInt(1, 3));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShatterHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player target)) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isShatterSword(weapon)) return;
        String swordId = weapon.getItemMeta().getPersistentDataContainer().get(shatterIdKey, PersistentDataType.STRING);
        if (swordId == null || !paidShatterIds.contains(swordId) || usedShatterIds.contains(swordId)) return;
        if (event.getFinalDamage() <= 0 || swordUseLocks.contains(attacker.getUniqueId())) return;
        List<RelicService.EligibleRelic> eligible = relics.highestTierRelics(target);
        if (eligible.isEmpty()) {
            target.getWorld().strikeLightningEffect(target.getLocation());
            attacker.sendMessage(plugin.message("&7No eligible relic was found; the Shatter Sword remains intact."));
            return;
        }
        swordUseLocks.add(attacker.getUniqueId());
        RelicService.EligibleRelic selected = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        usedShatterIds.add(swordId);
        if (!saveState()) {
            usedShatterIds.remove(swordId);
            swordUseLocks.remove(attacker.getUniqueId());
            attacker.sendMessage(plugin.message("&cThe Shatter Sword effect could not be saved; your sword was retained."));
            return;
        }
        weapon.setAmount(weapon.getAmount() - 1);
        target.getWorld().strikeLightningEffect(target.getLocation());
        long expires = System.currentTimeMillis() + 30L * 60 * 1000;
        disabledRelics.computeIfAbsent(target.getUniqueId(), ignored -> new HashMap<>())
                .put(selected.clazz() + ":" + selected.ability(), expires);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreRelic(target.getUniqueId(), selected, expires), 30L * 60 * 20);
        attacker.sendMessage(plugin.message("&dShatter Sword consumed; " + selected.ability() + " was disabled for 30 minutes."));
        target.sendMessage(plugin.message("&5Your " + selected.ability() + " relic was disabled for 30 minutes."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> swordUseLocks.remove(attacker.getUniqueId()), 1L);
    }

    private void restoreRelic(UUID playerId, RelicService.EligibleRelic selected, long expires) {
        Map<String, Long> disabled = disabledRelics.get(playerId);
        String key = selected.clazz() + ":" + selected.ability();
        if (disabled == null || !java.util.Objects.equals(disabled.get(key), expires)) return;
        disabled.remove(key);
        if (disabled.isEmpty()) disabledRelics.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) player.sendMessage(plugin.message("&aYour " + selected.ability() + " relic is restored."));
    }

    boolean isDisabled(UUID playerId, String ability) {
        long now = System.currentTimeMillis();
        String clazz = plugin.selectedClass(playerId);
        return angelMarkedUntil.getOrDefault(playerId, 0L) > now
                || clazz != null && disabledRelics.getOrDefault(playerId, Map.of())
                .getOrDefault(clazz + ":" + ability, 0L) > now;
    }

    boolean isDisabled(UUID playerId) { return angelMarkedUntil.getOrDefault(playerId, 0L) > System.currentTimeMillis(); }

    private void expireOldDisables() {
        long now = System.currentTimeMillis();
        disabledRelics.values().forEach(map -> map.entrySet().removeIf(entry -> entry.getValue() <= now));
        disabledRelics.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        angelMarkedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        saveState();
    }

    private boolean saveState() {
        state.set("claimed-boxes", new ArrayList<>(claimedBoxes));
        state.set("paid-shatter-swords", new ArrayList<>(paidShatterIds));
        state.set("used-shatter-swords", new ArrayList<>(usedShatterIds));
        try { state.save(stateFile); return true; }
        catch (IOException ex) { plugin.getLogger().severe("Could not persist relic encounter state: " + ex.getMessage()); return false; }
    }
}
