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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
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
    private final NamespacedKey villagerKey, questKey, shatterKey, shatterIdKey, shatterPaidKey, villagerQuestTypeKey, perfectOfferKey, punishmentWardenKey, punishmentDeathKey, punishmentSlotKey, uniqueHeartKey, crazyTreasureHeartKey, crazyTreasureChestKey, crazyRewardDeliveryKey, crazyConsumedItemKey, waveKey, wandererTradeItemKey, angelPurchaseReceiptKey, angelPaymentReceiptKey;
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
    // Legacy UUID-only receipts remain readable; new deaths use replayable records below.
    private final Set<UUID> legacyAngelDeathsProcessed = new LinkedHashSet<>();
    private final Set<String> claimedBoxes = new HashSet<>();
    private final Map<UUID, Set<UUID>> angelDamagers = new HashMap<>();
    private final Map<UUID, List<TradeOffer>> angelOffers = new HashMap<>();
    private final Map<UUID, AngelSpawnBoundary.SpawnPosition> angelSpawnLocations = new HashMap<>();
    private final Map<String, Integer> excludedTypes = new HashMap<>();
    private String currentWaveId = "";
    private long currentWaveExpiresAt;
    private int waveExpiryTask = -1;

    private record Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed,
                         String heartId, long expiresAt, long rewardMarks) {
        Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed) {
            this(owner, world, x, y, z, type, claimed, "", 0L, 0L);
        }
        Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed, String heartId, long expiresAt) {
            this(owner, world, x, y, z, type, claimed, heartId, expiresAt, 0L);
        }
        Quest withReward(long reward) { return new Quest(owner, world, x, y, z, type, claimed, heartId, expiresAt, reward); }
        Quest complete() { return new Quest(owner, world, x, y, z, type, true, heartId, expiresAt, rewardMarks); }
        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private record HuntQuest(UUID owner, String objective, String mobType, int required, int progress,
                            int diamonds, long marks, String itemType, String enchantment,
                            boolean foundTreasure, String questId) {
        HuntQuest(UUID owner, String mobType, int required, int progress, int diamonds, long marks) {
            this(owner, "hunt", mobType, required, progress, diamonds, marks, "", "", false, UUID.randomUUID().toString());
        }
        HuntQuest progress(int next) {
            return new HuntQuest(owner, objective, mobType, required, next, diamonds, marks, itemType, enchantment, foundTreasure, questId);
        }
        HuntQuest treasureFound() {
            return new HuntQuest(owner, objective, mobType, required, progress, diamonds, marks, itemType, enchantment, true, questId);
        }
    }

    private record TradeOffer(ItemStack output, long marks, Material equipment, String requiredClass) { }

    private static final class TradeMenuHolder implements InventoryHolder {
        private final UUID angelId;
        private final List<TradeOffer> offers;
        private Inventory inventory;
        private TradeMenuHolder(UUID angelId, List<TradeOffer> offers) { this.angelId = angelId; this.offers = offers; }
        @Override public Inventory getInventory() { return inventory; }
    }

    RelicEncounterService(mark plugin, RelicService relics) {
        this.plugin = plugin;
        this.relics = relics;
        villagerKey = new NamespacedKey(plugin, "relic_wave_villager");
        questKey = new NamespacedKey(plugin, "relic_quest_token");
        villagerQuestTypeKey = new NamespacedKey(plugin, "relic_villager_quest_type");
        perfectOfferKey = new NamespacedKey(plugin, "angel_perfect_offer");
        punishmentWardenKey = new NamespacedKey(plugin, "angel_punishment_warden");
        punishmentDeathKey = new NamespacedKey(plugin, "angel_punishment_death");
        punishmentSlotKey = new NamespacedKey(plugin, "angel_punishment_slot");
        uniqueHeartKey = new NamespacedKey(plugin, "wanderer_unique_heart");
        crazyTreasureHeartKey = new NamespacedKey(plugin, "crazy_treasure_heart");
        crazyTreasureChestKey = new NamespacedKey(plugin, "crazy_treasure_chest");
        crazyRewardDeliveryKey = new NamespacedKey(plugin, "crazy_reward_delivery");
        crazyConsumedItemKey = new NamespacedKey(plugin, "crazy_consumed_submission");
        wandererTradeItemKey = new NamespacedKey(plugin, "wanderer_trade_item_id");
        angelPurchaseReceiptKey = new NamespacedKey(plugin, "angel_purchase_receipt");
        angelPaymentReceiptKey = new NamespacedKey(plugin, "angel_payment_receipt");
        waveKey = new NamespacedKey(plugin, "relic_wave_id");
        shatterKey = new NamespacedKey(plugin, "shatter_sword");
        shatterIdKey = new NamespacedKey(plugin, "shatter_id");
        shatterPaidKey = new NamespacedKey(plugin, "shatter_paid");
        stateFile = new File(plugin.getDataFolder(), "relic-encounters.yml");
        state = SafeYaml.load(stateFile);
        paidShatterIds.addAll(state.getStringList("paid-shatter-swords"));
        usedShatterIds.addAll(state.getStringList("used-shatter-swords"));
        var exclusions = state.getConfigurationSection("excluded-angel-types");
        if (exclusions != null) for (String type : exclusions.getKeys(false)) {
            int waves = state.getInt("excluded-angel-types." + type, 0);
            if (waves > 0 && List.of("crazy", "imperfect", "perfect", "ascendant", "wanderer").contains(type)) excludedTypes.put(type, Math.min(3, waves));
        }
        claimedBoxes.addAll(state.getStringList("claimed-boxes"));
        for (String id : state.getStringList("processed-angel-deaths")) {
            try { legacyAngelDeathsProcessed.add(UUID.fromString(id)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Ignoring invalid processed Angel death ID: " + id); }
        }
        var marked = state.getConfigurationSection("angel-marked-until");
        if (marked != null) for (String id : marked.getKeys(false)) {
            try {
                long expires = state.getLong("angel-marked-until." + id, 0L);
                if (expires > System.currentTimeMillis()) angelMarkedUntil.put(UUID.fromString(id), expires);
            } catch (IllegalArgumentException ex) { plugin.getLogger().warning("Ignoring invalid Angel mark UUID: " + id); }
        }
        for (String key : state.getConfigurationSection("quests") == null
                ? Collections.<String>emptySet() : state.getConfigurationSection("quests").getKeys(false)) {
            String path = "quests." + key;
            try {
                Quest quest = new Quest(UUID.fromString(state.getString(path + ".owner")),
                        state.getString(path + ".world"), state.getInt(path + ".x"), state.getInt(path + ".y"),
                        state.getInt(path + ".z"), state.getString(path + ".type", "nether"), state.getBoolean(path + ".claimed"),
                        state.getString(path + ".heart-id", ""), state.getLong(path + ".expires-at", 0L),
                        Math.max(0L, state.getLong(path + ".reward-marks", 0L)));
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
                String objective = state.getString(path + ".objective", "hunt");
                huntQuests.put(owner, new HuntQuest(owner, objective, state.getString(path + ".mob", "ZOMBIE"),
                        state.getInt(path + ".required", 5), state.getInt(path + ".progress", 0),
                        state.getInt(path + ".diamonds", 3), state.getLong(path + ".marks", 0),
                        state.getString(path + ".item", ""), state.getString(path + ".enchantment", ""),
                        state.getBoolean(path + ".found-treasure", false), state.getString(path + ".quest-id", UUID.randomUUID().toString())));
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
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(player -> {
            recoverAngelPurchases(player);
            deliverPendingCrazyRewards(player);
        }));
        Bukkit.getScheduler().runTask(plugin, this::recoverAngelDeaths);
        Bukkit.getScheduler().runTaskTimer(plugin, this::recoverAngelDeaths, 20L * 10, 20L * 30);
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnWave, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnWave, interval, interval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireOldDisables, 20L * 30, 20L * 30);
    }

    void shutdown() {
        recoverAngelDeaths();
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
            Location actualLocation = villager.getLocation();
            if (!isWithinAngelSpawnArea(actualLocation)) {
                villager.remove();
                plugin.getLogger().warning("Discarded an Angel spawn outside the permitted world-border area in "
                        + actualLocation.getWorld().getName() + " at " + actualLocation.getX() + ", "
                        + actualLocation.getY() + ", " + actualLocation.getZ());
                continue;
            }
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
            AngelSpawnBoundary.recordSuccessfulSpawn(angelSpawnLocations, villager.getUniqueId(),
                    actualLocation.getWorld().getName(), actualLocation.getX(), actualLocation.getY(), actualLocation.getZ());
            villager.getPersistentDataContainer().set(villagerKey, PersistentDataType.BYTE, (byte) 1);
            villager.getPersistentDataContainer().set(waveKey, PersistentDataType.STRING, waveId);
            String questType = waveTypes.get(i);
            villager.getPersistentDataContainer().set(villagerQuestTypeKey, PersistentDataType.STRING, questType);
            boolean bonusPerfect = questType.equals("imperfect") && bonusSlots.contains(i);
            villager.getPersistentDataContainer().set(perfectOfferKey, PersistentDataType.BYTE, (byte) (bonusPerfect ? 1 : 0));
            villager.setRecipes(Collections.emptyList());
            if (List.of("imperfect", "perfect", "ascendant", "wanderer").contains(questType))
                angelOffers.put(villager.getUniqueId(), generateOffers(questType, bonusPerfect));
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
                    angelSpawnLocations.remove(villager.getUniqueId());
                    villager.remove();
                }
            }
        }
        angelDamagers.clear();
        angelOffers.clear();
        angelSpawnLocations.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Warden warden) retargetPunishmentWarden(warden);
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
                if (!isWithinAngelSpawnArea(loc)) continue;
                // Structure lookup does not load chunks. This loop is bounded to 18 candidates.
                loc.getChunk().load();
                if (loc.getBlock().getType().isSolid()) loc.add(0, 1, 0);
                if (loc.getBlock().isPassable() && loc.clone().add(0, 1, 0).getBlock().isPassable()) {
                    if (isWithinAngelSpawnArea(loc)) return new SpawnPoint(loc, choice.name());
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().fine("Structure unavailable on this server version: " + choice.name());
            }
        }
        // Bounded fallback around each world's real spawn. Border validation precedes chunk access.
        for (World world : worlds) {
            Location worldSpawn = world.getSpawnLocation();
            for (int attempt = 0; attempt < 18; attempt++) {
                int dx = attempt == 0 ? 0 : ThreadLocalRandom.current().nextInt(-12, 13);
                int dz = attempt == 0 ? 0 : ThreadLocalRandom.current().nextInt(-12, 13);
                Location candidate = new Location(world, worldSpawn.getX() + dx + 0.5, worldSpawn.getY(), worldSpawn.getZ() + dz + 0.5);
                if (!isWithinAngelSpawnArea(candidate)) continue;
                candidate.getChunk().load();
                candidate.setY(world.getHighestBlockYAt(candidate) + 1);
                if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                        && isWithinAngelSpawnArea(candidate)) return new SpawnPoint(candidate, "WORLD_SPAWN");
            }
        }
        plugin.getLogger().warning("Could not find a safe Angel spawn inside the permitted world-border area after bounded attempts; this spawn was skipped.");
        return null;
    }

    private boolean isWithinAngelSpawnArea(Location location) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        var border = world.getWorldBorder();
        Location center = border.getCenter();
        Location spawn = world.getSpawnLocation();
        return border.isInside(location) && AngelSpawnBoundary.contains(center.getX(), center.getZ(), border.getSize(),
                spawn.getX(), spawn.getZ(), location.getX(), location.getZ());
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

    private List<TradeOffer> generateOffers(String angelType, boolean bonusPerfect) {
        List<TradeOffer> offers = new ArrayList<>();
        String[] classes = {"vanguard", "phantom", "warden"};
        if (angelType.equals("imperfect")) {
            String clazz = classes[ThreadLocalRandom.current().nextInt(classes.length)];
            offers.add(relicOffer(clazz, 1, relicPrice(1), null));
            if (bonusPerfect) offers.add(relicOffer(classes[ThreadLocalRandom.current().nextInt(classes.length)], 2, relicPrice(2), null));
        } else if (angelType.equals("perfect")) {
            int count = ThreadLocalRandom.current().nextInt(2, 5);
            for (int i = 0; i < count; i++)
                offers.add(relicOffer(classes[ThreadLocalRandom.current().nextInt(classes.length)], 2, relicPrice(2), null));
        } else if (angelType.equals("ascendant")) {
            for (String clazz : classes) {
                offers.add(relicOffer(clazz, 1, relicPrice(1), null, clazz));
                offers.add(relicOffer(clazz, 2, relicPrice(2), null, clazz));
                offers.add(relicOffer(clazz, 3, relicPrice(3), ascendantEquipment(clazz), clazz));
            }
        } else if (angelType.equals("wanderer")) {
            var custom = plugin.getConfig().getConfigurationSection("relic-villagers.wanderer-custom-offers");
            if (custom != null) {
                List<String> keys = new ArrayList<>(custom.getKeys(false));
                Collections.shuffle(keys);
                for (String key : keys) {
                    try {
                        ItemStack item = custom.getItemStack(key + ".item");
                        long price = custom.getLong(key + ".marks", -1);
                        if (item == null || item.getType().isAir() || item.getAmount() <= 0
                                || item.getAmount() > item.getMaxStackSize() || price <= 0 || item.getItemMeta() == null) {
                            plugin.getLogger().warning("Ignoring invalid Wanderer custom offer: " + key + " (requires a valid serialized item and positive Marks price)");
                            continue;
                        }
                        offers.add(new TradeOffer(item.clone(), price, null, null));
                        if (offers.size() == 6) break;
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Ignoring malformed Wanderer custom offer " + key + ": " + ex.getMessage());
                    }
                }
            }
        }
        return List.copyOf(offers);
    }

    private long relicPrice(int tier) {
        long fallback = tier == 1 ? 1_000 : 2_000;
        long configured = plugin.getConfig().getLong("relics.prices.level-" + tier, fallback);
        if (configured <= 0) {
            plugin.getLogger().warning("Invalid relic price for tier " + tier + "; using " + fallback + " Marks.");
            return fallback;
        }
        return configured;
    }

    private TradeOffer relicOffer(String clazz, int tier, long price, Material equipment) {
        return new TradeOffer(relics.createRelic(clazz, relicAbility(clazz, tier), tier), price, equipment, null);
    }

    private TradeOffer relicOffer(String clazz, int tier, long price, Material equipment, String requiredClass) {
        return new TradeOffer(relics.createRelic(clazz, relicAbility(clazz, tier), tier), price, equipment, requiredClass);
    }

    private Material ascendantEquipment(String clazz) {
        return switch (clazz) {
            case "vanguard" -> Material.NETHERITE_SWORD;
            case "warden" -> Material.NETHERITE_CHESTPLATE;
            case "phantom" -> Material.NETHERITE_BOOTS;
            default -> throw new IllegalArgumentException("Unknown relic class " + clazz);
        };
    }

    private void openAngelOffers(Player player, Villager angel) {
        List<TradeOffer> all = angelOffers.get(angel.getUniqueId());
        if (all == null || all.isEmpty()) {
            player.sendMessage(plugin.message("&7This Angel has no configured offers."));
            return;
        }
        String clazz = plugin.selectedClass(player.getUniqueId());
        List<TradeOffer> visible = all.stream().filter(offer -> offer.requiredClass() == null || offer.requiredClass().equals(clazz)).limit(45).toList();
        if (visible.isEmpty()) {
            player.sendMessage(plugin.message("&cChoose a class to view this Angel's offers."));
            return;
        }
        TradeMenuHolder holder = new TradeMenuHolder(angel.getUniqueId(), visible);
        int size = Math.min(54, Math.max(9, ((visible.size() + 8) / 9) * 9));
        Inventory menu = Bukkit.createInventory(holder, size, ChatColor.DARK_PURPLE + capitalize(angel.getPersistentDataContainer()
                .getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "angel")) + " Angel Offers");
        holder.inventory = menu;
        for (int i = 0; i < visible.size(); i++) {
            TradeOffer offer = visible.get(i);
            ItemStack display = offer.output().clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta != null && meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GOLD + "Cost: " + offer.marks() + " Marks");
            if (offer.equipment() != null) lore.add(ChatColor.GRAY + "Also requires: " + prettyMaterial(offer.equipment()));
            lore.add(ChatColor.GREEN + "Click to purchase");
            meta.setLore(lore);
            display.setItemMeta(meta);
            menu.setItem(i, display);
        }
        player.openInventory(menu);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAngelTradeClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder() instanceof TradeMenuHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize() || slot >= holder.offers.size()) return;
        if (!angelOffers.containsKey(holder.angelId)) {
            player.closeInventory();
            player.sendMessage(plugin.message("&cThis Angel's offers have expired."));
            return;
        }
        purchaseAngelOffer(player, holder.offers.get(slot));
    }

    @EventHandler(ignoreCancelled = true)
    public void onAngelTradeDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenuHolder) event.setCancelled(true);
    }

    private void purchaseAngelOffer(Player player, TradeOffer offer) {
        if (offer.requiredClass() != null && !offer.requiredClass().equals(plugin.selectedClass(player.getUniqueId()))) {
            player.sendMessage(plugin.message("&cThis offer is only available to the matching class."));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack reward = offer.output().clone();
        if (reward.getAmount() > reward.getMaxStackSize()) {
            player.sendMessage(plugin.message("&cMake room in your inventory before purchasing."));
            return;
        }
        int equipmentSlot = offer.equipment() == null ? -1 : findPayment(inventory, offer.equipment());
        if (offer.equipment() != null && equipmentSlot < 0) {
            player.sendMessage(plugin.message("&cThis offer requires " + prettyMaterial(offer.equipment()) + "."));
            return;
        }
        boolean shatter = isShatterSword(reward);
        if (shatter && offer.marks() > Long.MAX_VALUE - 500) {
            player.sendMessage(plugin.message("&cThis offer has an invalid total price."));
            return;
        }
        long total = offer.marks() + (shatter ? 500 : 0);
        String transactionId = UUID.randomUUID().toString();
        ItemStack payment = equipmentSlot < 0 ? null : inventory.getItem(equipmentSlot).clone();
        if (payment != null) {
            ItemMeta paymentMeta = payment.getItemMeta();
            paymentMeta.getPersistentDataContainer().set(angelPaymentReceiptKey, PersistentDataType.STRING, transactionId);
            payment.setItemMeta(paymentMeta);
        }
        if (!shatter) tagWandererTradeItem(reward);
        ItemMeta rewardMeta = reward.getItemMeta();
        if (rewardMeta == null) return;
        rewardMeta.getPersistentDataContainer().set(angelPurchaseReceiptKey, PersistentDataType.STRING, transactionId);
        if (shatter) {
            String swordId = UUID.randomUUID().toString();
            rewardMeta.getPersistentDataContainer().set(shatterIdKey, PersistentDataType.STRING, swordId);
            rewardMeta.getPersistentDataContainer().set(shatterPaidKey, PersistentDataType.BYTE, (byte) 1);
            state.set("angel-purchases." + transactionId + ".shatter-id", swordId);
        }
        reward.setItemMeta(rewardMeta);
        if (!canFit(inventory, reward)) {
            player.sendMessage(plugin.message("&cMake room in your inventory before purchasing."));
            return;
        }
        String transactionPath = "angel-purchases." + transactionId;
        state.set(transactionPath + ".owner", player.getUniqueId().toString());
        state.set(transactionPath + ".marks", total);
        state.set(transactionPath + ".equipment", payment);
        state.set(transactionPath + ".reward", reward);
        state.set(transactionPath + ".status", "prepared");
        if (!saveState()) {
            state.set(transactionPath, null);
            player.sendMessage(plugin.message("&cThe trade could not be prepared safely; no payment was taken."));
            return;
        }
        if (payment != null) {
            ItemStack heldPayment = inventory.getItem(equipmentSlot);
            ItemMeta marked = heldPayment.getItemMeta();
            marked.getPersistentDataContainer().set(angelPaymentReceiptKey, PersistentDataType.STRING, transactionId);
            heldPayment.setItemMeta(marked);
            inventory.setItem(equipmentSlot, heldPayment);
            player.saveData();
        }
        if (!plugin.debitOnce(player.getUniqueId(), total, transactionId)) {
            recoverAngelPurchase(player, transactionId);
            player.sendMessage(plugin.message("&cYou need " + total + " Marks for this offer."));
            return;
        }
        if (shatter) {
            String swordId = state.getString(transactionPath + ".shatter-id");
            if (swordId != null) paidShatterIds.add(swordId);
        }
        state.set(transactionPath + ".status", "charged");
        if (!saveState()) {
            recoverAngelPurchase(player, transactionId);
            player.sendMessage(plugin.message("&cTrade recovery was started because its journal could not be updated."));
            return;
        }
        if (payment != null) {
            int currentPaymentSlot = findMarkedInventorySlot(inventory, angelPaymentReceiptKey, transactionId);
            if (currentPaymentSlot >= 0) consumeOne(inventory, currentPaymentSlot);
        }
        Map<Integer, ItemStack> overflow = inventory.addItem(reward);
        if (!overflow.isEmpty()) {
            recoverAngelPurchase(player, transactionId);
            player.sendMessage(plugin.message("&cThe trade could not be delivered; payment was returned."));
            return;
        }
        player.saveData();
        state.set(transactionPath + ".status", "complete");
        if (!saveState()) plugin.getLogger().severe("Angel purchase delivered but journal finalization failed; recovery will inspect item receipt " + transactionId);
        player.updateInventory();
        player.sendMessage(plugin.message("&aTrade complete. Paid " + total + " Marks."));
    }

    private int findMarkedInventorySlot(PlayerInventory inventory, NamespacedKey key, String id) {
        ItemStack[] items = inventory.getContents();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item != null && item.hasItemMeta() && id.equals(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING))) return slot;
        }
        return -1;
    }

    private boolean hasTransactionItem(Player player, NamespacedKey key, String id) {
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && item.hasItemMeta() && id.equals(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING))) return true;
        for (ItemStack item : player.getEnderChest().getContents())
            if (item != null && item.hasItemMeta() && id.equals(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING))) return true;
        return false;
    }

    private void recoverAngelPurchases(Player player) {
        var section = state.getConfigurationSection("angel-purchases");
        if (section == null) return;
        for (String id : new ArrayList<>(section.getKeys(false))) {
            String path = "angel-purchases." + id;
            if (!player.getUniqueId().toString().equals(state.getString(path + ".owner"))) continue;
            recoverAngelPurchase(player, id);
        }
    }

    private void recoverAngelPurchase(Player player, String id) {
        String path = "angel-purchases." + id;
        String status = state.getString(path + ".status", "prepared");
        if (status.equals("complete") || status.equals("cancelled")
                || !player.getUniqueId().toString().equals(state.getString(path + ".owner"))) return;
        if (!plugin.hasDebitReceipt(id)) {
            int slot = findMarkedInventorySlot(player.getInventory(), angelPaymentReceiptKey, id);
            if (slot >= 0) clearTransactionTag(player.getInventory().getItem(slot), angelPaymentReceiptKey);
            state.set(path + ".status", "cancelled");
            saveState();
            player.saveData();
            return;
        }
        if (hasTransactionItem(player, angelPurchaseReceiptKey, id)) {
            String swordId = state.getString(path + ".shatter-id");
            if (swordId != null) paidShatterIds.add(swordId);
            state.set(path + ".status", "complete");
            saveState();
            return;
        }
        int paymentSlot = findMarkedInventorySlot(player.getInventory(), angelPaymentReceiptKey, id);
        ItemStack expectedPayment = state.getItemStack(path + ".equipment");
        ItemStack markedPayment = paymentSlot < 0 ? null : player.getInventory().getItem(paymentSlot);
        int paymentUnitsMissing = expectedPayment == null ? 0
                : Math.max(0, expectedPayment.getAmount() - (markedPayment == null ? 0 : markedPayment.getAmount()));
        if (paymentUnitsMissing > 0) {
            ItemStack restored = expectedPayment.clone();
            restored.setAmount(paymentUnitsMissing);
            ItemMeta meta = restored.getItemMeta();
            meta.getPersistentDataContainer().set(angelPaymentReceiptKey, PersistentDataType.STRING, id);
            restored.setItemMeta(meta);
            ItemStack[] before = cloneInventoryItems(player.getInventory().getContents());
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(restored);
            if (!overflow.isEmpty()) {
                player.getInventory().setContents(before);
                return;
            }
        }
        player.saveData();
        if (!plugin.refundDebitOnce(player.getUniqueId(), id)) return;
        String swordId = state.getString(path + ".shatter-id");
        if (swordId != null) paidShatterIds.remove(swordId);
        state.set(path + ".status", "cancelled");
        if (!saveState()) return;
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && item.hasItemMeta()
                    && id.equals(item.getItemMeta().getPersistentDataContainer().get(angelPaymentReceiptKey, PersistentDataType.STRING)))
                clearTransactionTag(item, angelPaymentReceiptKey);
        player.saveData();
    }

    private void clearTransactionTag(ItemStack item, NamespacedKey key) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(key);
        item.setItemMeta(meta);
    }

    @EventHandler
    public void onRecoveryJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            recoverAngelPurchases(event.getPlayer());
            deliverPendingCrazyRewards(event.getPlayer());
        });
    }

    @EventHandler
    public void onRecoveryInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            recoverAngelPurchases(player);
            deliverPendingCrazyRewards(player);
        });
    }

    private boolean canFit(PlayerInventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) remaining -= item.getMaxStackSize();
            else if (current.isSimilar(item)) remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private int findPayment(PlayerInventory inventory, Material material) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) if (contents[i] != null && contents[i].getType() == material && contents[i].getAmount() > 0) return i;
        return -1;
    }

    private void consumeOne(PlayerInventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        if (item.getAmount() <= 1) inventory.setItem(slot, null);
        else item.setAmount(item.getAmount() - 1);
    }

    private void tagWandererTradeItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(wandererTradeItemKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        item.setItemMeta(meta);
    }

    private String prettyMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
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
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        String type = villager.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "crazy");
        event.setCancelled(true);
        if (type.equals("crazy")) {
            if (!submitCrazyQuestItem(event.getPlayer())) {
                HuntQuest quest = huntQuests.get(event.getPlayer().getUniqueId());
                if (quest != null && !quest.objective().equals("hunt"))
                    event.getPlayer().sendMessage(plugin.message(quest.objective().equals("treasure") && !quest.foundTreasure()
                            ? "&cFind a Buried Treasure chest before submitting its Heart of the Sea."
                            : "&cHold the required quest item and right-click this Angel to submit it."));
                else event.getPlayer().sendMessage(plugin.message("&cCrazy Angel: left-click to receive a persistent quest."));
            }
        }
        else if (type.equals("wanderer") && submitWandererHeart(event.getPlayer())) return;
        else openAngelOffers(event.getPlayer(), villager);
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
            UUID angelId = angel.getUniqueId(); // Entity UUID is stable and unique for this encounter's death.
            if (legacyAngelDeathsProcessed.contains(angelId)) return;
            String path = "angel-death-consequences." + angelId;
            if (!state.getBoolean(path + ".recorded", false)) {
                Set<UUID> contributors = new LinkedHashSet<>(angelDamagers.getOrDefault(angelId, Set.of()));
                Player killer = playerDamager(angel.getLastDamageCause() instanceof EntityDamageByEntityEvent hit ? hit : null);
                if (killer == null) killer = angel.getKiller();
                if (killer != null) contributors.add(killer.getUniqueId());
                Location at = killer == null ? angel.getLocation() : killer.getLocation();
                String type = angel.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "imperfect");
                state.set(path + ".victim", angelId.toString());
                state.set(path + ".killer", killer == null ? "" : killer.getUniqueId().toString());
                state.set(path + ".contributors", contributors.stream().map(UUID::toString).toList());
                state.set(path + ".angel-type", type);
                state.set(path + ".death-time", System.currentTimeMillis());
                state.set(path + ".spawn.world", at.getWorld().getName());
                state.set(path + ".spawn.x", at.getX());
                state.set(path + ".spawn.y", at.getY());
                state.set(path + ".spawn.z", at.getZ());
                state.set(path + ".recorded", true);
                if (!saveState()) {
                    state.set(path, null);
                    plugin.getLogger().severe("Could not persist consequences for Angel death " + angelId + "; consequences will not be applied until the record can be saved.");
                    return;
                }
            }
            processAngelDeath(angelId.toString());
            angelDamagers.remove(angelId);
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) return;
        if (punishmentDeathsProcessed.contains(victim.getUniqueId())) return;
        EntityDamageByEntityEvent lastHit = victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hit ? hit : null;
        if (lastHit == null || !(lastHit.getDamager() instanceof Warden warden)
                || !warden.getPersistentDataContainer().has(punishmentWardenKey, PersistentDataType.BYTE)) return;
        if (!punishmentDeathsProcessed.add(victim.getUniqueId())) return;
        int removed = relics.removeGenuineRelics(victim);
        victim.sendMessage(plugin.message("&4A punishment Warden destroyed " + removed + " genuine relic item(s) and all applied relic tiers."));
        plugin.audit("Tracked Angel punishment Warden deleted " + removed + " carried genuine relics and applied relic tiers from " + victim.getUniqueId());
    }

    private void recoverAngelDeaths() {
        var records = state.getConfigurationSection("angel-death-consequences");
        if (records == null) return;
        for (String id : new ArrayList<>(records.getKeys(false))) {
            if (state.getBoolean("angel-death-consequences." + id + ".recorded", false)
                    && !state.getBoolean("angel-death-consequences." + id + ".complete", false)) {
                processAngelDeath(id);
            }
        }
    }

    private void processAngelDeath(String deathId) {
        String path = "angel-death-consequences." + deathId;
        UUID victim;
        try { victim = UUID.fromString(state.getString(path + ".victim", "")); }
        catch (IllegalArgumentException ex) {
            plugin.getLogger().severe("Angel death record has an invalid victim UUID and was left untouched: " + deathId);
            return;
        }
        List<String> contributors = state.getStringList(path + ".contributors");
        for (String contributorText : contributors) {
            UUID contributor;
            try { contributor = UUID.fromString(contributorText); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ignoring invalid contributor in Angel death record " + deathId + ": " + contributorText);
                continue;
            }
            String consequence = path + ".penalties." + contributor;
            if (state.getBoolean(consequence, false)) continue;
            if (!plugin.deductUpToOnce(contributor, 1_000, "angel:" + deathId + ":" + contributor)) return;
            state.set(consequence, true);
            if (!saveState()) { state.set(consequence, false); return; }
            plugin.audit("Angel kill penalty up to 1000 Marks target=" + contributor + " angel-death=" + deathId);
        }

        String killerText = state.getString(path + ".killer", "");
        if (!state.getBoolean(path + ".killer-disable-complete", false) && !killerText.isBlank()) {
            try {
                UUID killer = UUID.fromString(killerText);
                long until = state.getLong(path + ".death-time", System.currentTimeMillis()) + 30L * 60 * 1000;
                Long previous = angelMarkedUntil.get(killer);
                long merged = Math.max(previous == null ? 0L : previous, until);
                angelMarkedUntil.put(killer, merged);
                state.set("angel-marked-until." + killer, merged);
                state.set(path + ".killer-disable-complete", true);
                if (!saveState()) {
                    if (previous == null) angelMarkedUntil.remove(killer);
                    else angelMarkedUntil.put(killer, previous);
                    state.set("angel-marked-until." + killer, previous);
                    state.set(path + ".killer-disable-complete", false);
                    return;
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().severe("Angel death record has invalid killer UUID: " + deathId);
                return;
            }
        } else if (killerText.isBlank() && !state.getBoolean(path + ".killer-disable-complete", false)) {
            state.set(path + ".killer-disable-complete", true);
        }

        if (!state.getBoolean(path + ".exclusion-complete", false)) {
            String type = state.getString(path + ".angel-type", "");
            if (!List.of("crazy", "imperfect", "perfect", "ascendant", "wanderer").contains(type)) {
                plugin.getLogger().severe("Angel death record has invalid Angel type; exclusion remains pending: " + deathId);
                return;
            }
            excludedTypes.put(type, Math.max(3, excludedTypes.getOrDefault(type, 0)));
            state.set("excluded-angel-types." + type, excludedTypes.get(type));
            state.set(path + ".exclusion-complete", true);
            if (!saveState()) { state.set(path + ".exclusion-complete", false); return; }
        }

        Location spawnAt = deathSpawnLocation(path);
        if (spawnAt == null) {
            plugin.getLogger().severe("Could not resolve saved Warden spawn world for Angel death " + deathId + "; Warden consequence remains pending.");
            return;
        }
        for (int slot = 0; slot < 3; slot++) {
            String slotPath = path + ".wardens." + slot;
            if (state.getBoolean(slotPath + ".spawned", false)) continue;
            Location point = spawnAt.clone().add((slot - 1) * 2.0, 0, 1.5);
            Warden existing = findPunishmentWarden(point, deathId, slot);
            if (existing == null) {
                existing = (Warden) point.getWorld().spawnEntity(point, EntityType.WARDEN);
                existing.getPersistentDataContainer().set(punishmentWardenKey, PersistentDataType.BYTE, (byte) 1);
                existing.getPersistentDataContainer().set(punishmentDeathKey, PersistentDataType.STRING, deathId);
                existing.getPersistentDataContainer().set(punishmentSlotKey, PersistentDataType.INTEGER, slot);
                existing.setPersistent(true);
                existing.setRemoveWhenFarAway(false);
            }
            if (!killerText.isBlank()) {
                try {
                    Player target = Bukkit.getPlayer(UUID.fromString(killerText));
                    if (target != null) existing.setTarget(target);
                } catch (IllegalArgumentException ignored) { }
            }
            state.set(slotPath + ".uuid", existing.getUniqueId().toString());
            state.set(slotPath + ".spawned", true);
            if (!saveState()) {
                state.set(slotPath + ".uuid", null);
                state.set(slotPath + ".spawned", false);
                return;
            }
        }
        if (!state.getBoolean(path + ".complete", false)) {
            state.set(path + ".complete", true);
            boolean finalized = saveState();
            if (!finalized) state.set(path + ".complete", false);
            if (finalized && !killerText.isBlank()) {
                Player killer = Bukkit.getPlayer(UUID.fromString(killerText));
                if (killer != null) killer.sendMessage(plugin.message("&cThe Angels mark you. Three Wardens are hunting you."));
            }
        }
    }

    private Location deathSpawnLocation(String path) {
        World world = Bukkit.getWorld(state.getString(path + ".spawn.world", ""));
        if (world == null) return null;
        Location location = new Location(world, state.getDouble(path + ".spawn.x"),
                state.getDouble(path + ".spawn.y"), state.getDouble(path + ".spawn.z"));
        location.getChunk().load();
        return location;
    }

    private Warden findPunishmentWarden(Location at, String deathId, int slot) {
        for (Entity entity : at.getChunk().getEntities()) {
            if (!(entity instanceof Warden warden)) continue;
            var pdc = warden.getPersistentDataContainer();
            if (deathId.equals(pdc.get(punishmentDeathKey, PersistentDataType.STRING))
                    && pdc.getOrDefault(punishmentSlotKey, PersistentDataType.INTEGER, -1) == slot) return warden;
        }
        return null;
    }

    private void retargetPunishmentWarden(Warden warden) {
        var pdc = warden.getPersistentDataContainer();
        if (!pdc.has(punishmentWardenKey, PersistentDataType.BYTE)) return;
        String deathId = pdc.get(punishmentDeathKey, PersistentDataType.STRING);
        if (deathId == null) return; // Legacy tagged Wardens remain recognized but have no replayable target record.
        String killer = state.getString("angel-death-consequences." + deathId + ".killer", "");
        if (killer == null || killer.isBlank()) return;
        try {
            Player target = Bukkit.getPlayer(UUID.fromString(killer));
            if (target != null) warden.setTarget(target);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid killer in punishment Warden record " + deathId);
        }
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
        long reward = quest.rewardMarks() > 0 ? quest.rewardMarks() : ThreadLocalRandom.current().nextLong(400, 501);
        Quest pending = quest.withReward(reward);
        if (quest.rewardMarks() == 0 && !saveQuest(pending)) {
            player.sendMessage(plugin.message("&cYour submission could not be saved. Keep the Heart and try again."));
            return true;
        }
        quests.put(quest.owner(), pending);
        if (!plugin.creditOnce(player.getUniqueId(), reward, "Wanderer cache quest reward", "wanderer:" + quest.heartId())) {
            player.sendMessage(plugin.message("&cYour Marks reward could not be saved. Keep the Heart and try again."));
            return true;
        }
        Quest completed = pending.complete();
        if (!saveQuest(completed)) {
            player.sendMessage(plugin.message("&cYour reward is saved; completion will finish when you submit the Heart again."));
            return true;
        }
        quests.put(quest.owner(), completed);
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else if (mainHand) player.getInventory().setItemInMainHand(null);
        else player.getInventory().setItemInOffHand(null);
        removeQuestBlock(completed);
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
            if (!quest.type().equals("wanderer")) continue;
            if (quest.claimed()) {
                removeQuestBlock(quest);
                continue;
            }
            if (quest.rewardMarks() > 0) {
                if (!plugin.creditOnce(quest.owner(), quest.rewardMarks(), "Wanderer cache quest reward", "wanderer:" + quest.heartId())) continue;
                Quest completed = quest.complete();
                if (!saveQuest(completed)) { saveQuest(quest); continue; }
                quests.put(quest.owner(), completed);
                removeQuestBlock(completed);
                Player player = Bukkit.getPlayer(quest.owner());
                if (player != null) player.sendMessage(plugin.message("&aYour Wanderer quest was completed and its saved reward was delivered."));
                continue;
            }
            if (!plugin.applyWandererFailurePenalty(quest.owner(), 500, quest.heartId().isBlank() ? quest.owner() + ":" + quest.key() : quest.heartId())) continue;
            Quest failed = quest.complete();
            if (!saveQuest(failed)) { saveQuest(quest); continue; }
            quests.put(quest.owner(), failed);
            removeQuestBlock(failed);
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
        int roll = ThreadLocalRandom.current().nextInt(6);
        HuntQuest quest;
        String instruction;
        if (roll == 0) {
            quest = new HuntQuest(player.getUniqueId(), "WARDEN", 1, 0, 8, ThreadLocalRandom.current().nextLong(500, 1251));
            instruction = "&cCrazy Angel: Defeat a Warden. Reward: 8 diamonds and a Marks bonus.";
        } else if (roll == 1) {
            quest = new HuntQuest(player.getUniqueId(), "CREEPER", 5, 0, 5, ThreadLocalRandom.current().nextLong(500, 1251));
            instruction = "&cCrazy Angel: Defeat 5 Creepers. Reward: diamonds and Marks.";
        } else if (roll == 2) {
            quest = new HuntQuest(player.getUniqueId(), "SKELETON", 8, 0, 4, 0);
            instruction = "&cCrazy Angel: Defeat 8 Skeletons. Reward: a custom Angel Fragment.";
        } else if (roll == 3) {
            quest = new HuntQuest(player.getUniqueId(), "PILLAGER", 6, 0, 6, ThreadLocalRandom.current().nextLong(500, 1251));
            instruction = "&cCrazy Angel: Defeat 6 Pillagers. Reward: diamonds and Marks.";
        } else if (roll == 4) {
            quest = new HuntQuest(player.getUniqueId(), "submit", "", 1, 0, 0, 0,
                    "ENCHANTED_BOOK", "minecraft:mending", false, UUID.randomUUID().toString());
            instruction = "&cCrazy Angel: Submit an Enchanted Book with Mending. Right-click me while holding it.";
        } else {
            quest = new HuntQuest(player.getUniqueId(), "treasure", "", 1, 0, 0, 0,
                    "HEART_OF_THE_SEA", "", false, UUID.randomUUID().toString());
            instruction = "&cCrazy Angel: Find a Buried Treasure chest, then submit its Heart of the Sea here.";
        }
        if (!saveHuntQuest(quest)) {
            state.set("hunt-quests." + player.getUniqueId(), null);
            player.sendMessage(plugin.message("&cQuest data could not be saved; no quest was accepted."));
            return;
        }
        huntQuests.put(player.getUniqueId(), quest);
        player.sendMessage(plugin.message(instruction));
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
            player.sendMessage(plugin.message("&cNo safe underground cache location was found nearby. Try again."));
            return;
        }
        chosen.getChunk().load();
        String heartId = UUID.randomUUID().toString();
        long expiresAt = currentWaveExpiresAt;
        Quest quest = new Quest(player.getUniqueId(), world.getName(), chosen.getBlockX(), chosen.getBlockY(), chosen.getBlockZ(), "wanderer", false, heartId, expiresAt);
        quests.put(player.getUniqueId(), quest);
        if (!saveQuest(quest)) {
            quests.remove(player.getUniqueId());
            state.set("quests." + player.getUniqueId(), null);
            player.sendMessage(plugin.message("&cThe Wanderer quest could not be saved; no cache was created."));
            return;
        }
        List<ItemStack> contents = createWandererCacheContents(player, heartId);
        chosen.getBlock().setType(Material.SHULKER_BOX, false);
        if (chosen.getBlock().getState() instanceof org.bukkit.block.ShulkerBox box) {
            for (ItemStack item : contents) {
                Map<Integer, ItemStack> overflow = box.getInventory().addItem(item);
                for (ItemStack stack : overflow.values()) world.dropItemNaturally(chosen, stack);
            }
            box.getPersistentDataContainer().set(questKey, PersistentDataType.STRING, heartId);
            box.update(true, false);
        } else {
            quests.remove(player.getUniqueId());
            state.set("quests." + player.getUniqueId(), null);
            chosen.getBlock().setType(Material.AIR, false);
            saveState();
            player.sendMessage(plugin.message("&cThe cache block could not be initialized; the quest was not started."));
            return;
        }
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
        if (!saveHuntQuest(quest)) {
            state.set("hunt-quests." + player.getUniqueId(), null);
            player.sendMessage(plugin.message("&cQuest data could not be saved; no quest was accepted."));
            return;
        }
        huntQuests.put(player.getUniqueId(), quest);
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
        if (quest == null || !quest.objective().equals("hunt") || !event.getEntity().getType().name().equals(quest.mobType())) return;
        int progress = quest.progress() + 1;
        if (progress < quest.required()) {
            HuntQuest updated = quest.progress(progress);
            if (!saveHuntQuest(updated)) {
                saveHuntQuest(quest);
                killer.sendMessage(plugin.message("&cQuest progress could not be saved; this kill was not counted."));
                return;
            }
            huntQuests.put(killer.getUniqueId(), updated);
            killer.sendMessage(plugin.message("&dAngelic hunt: &f" + progress + "/" + quest.required() + " " + quest.mobType().toLowerCase(Locale.ROOT) + "s"));
            return;
        }
        List<ItemStack> rewards = new ArrayList<>();
        rewards.add(new ItemStack(Material.DIAMOND, quest.diamonds()));
        if (quest.mobType().equals("SKELETON")) rewards.add(createAngelFragment());
        rewards.add(randomCrazyReward());
        if (!claimCrazyQuest(quest, rewards, quest.marks(), null)) return;
        finishCrazyQuestRewards(killer, quest.questId());
        killer.sendMessage(plugin.message("&aAngelic hunt complete! You received " + quest.diamonds() + " diamonds"
                + (quest.marks() > 0 ? " and " + quest.marks() + " Marks." : "!")));
        killer.playSound(killer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
    }

    private boolean saveHuntQuest(HuntQuest quest) {
        String path = "hunt-quests." + quest.owner();
        state.set(path + ".objective", quest.objective());
        state.set(path + ".mob", quest.mobType());
        state.set(path + ".required", quest.required());
        state.set(path + ".progress", quest.progress());
        state.set(path + ".diamonds", quest.diamonds());
        state.set(path + ".marks", quest.marks());
        state.set(path + ".item", quest.itemType());
        state.set(path + ".enchantment", quest.enchantment());
        state.set(path + ".found-treasure", quest.foundTreasure());
        state.set(path + ".quest-id", quest.questId());
        return saveState();
    }

    private boolean saveQuest(Quest quest) {
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
        state.set(path + ".reward-marks", quest.rewardMarks());
        return saveState();
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

    @EventHandler(ignoreCancelled = true)
    public void onCrazyTreasureChest(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState() instanceof org.bukkit.block.Chest)) return;
        HuntQuest quest = huntQuests.get(event.getPlayer().getUniqueId());
        if (quest == null || !quest.objective().equals("treasure") || quest.foundTreasure()) return;
        var structure = Registry.STRUCTURE.get(NamespacedKey.minecraft("buried_treasure"));
        if (structure == null) return;
        Block target = event.getClickedBlock();
        boolean insideBuriedTreasure = target.getWorld().getStructures(target.getX() >> 4, target.getZ() >> 4, structure).stream()
                .anyMatch(generated -> generated.getBoundingBox().contains(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
        if (!insideBuriedTreasure) return;
        org.bukkit.block.Chest chest = (org.bukkit.block.Chest) target.getState();
        String linkedQuest = chest.getPersistentDataContainer().get(crazyTreasureChestKey, PersistentDataType.STRING);
        boolean alreadyValidatedForQuest = quest.questId().equals(linkedQuest);
        org.bukkit.loot.LootTable loot = chest.getLootTable();
        boolean buriedTreasureLoot = loot != null
                && loot.getKey().equals(NamespacedKey.minecraft("chests/buried_treasure"));
        if (!alreadyValidatedForQuest && !buriedTreasureLoot) return;
        chest.getPersistentDataContainer().set(crazyTreasureChestKey, PersistentDataType.STRING, quest.questId());
        chest.update(true, false);
        UUID owner = event.getPlayer().getUniqueId();
        String worldName = target.getWorld().getName();
        int x = target.getX(), y = target.getY(), z = target.getZ();
        Bukkit.getScheduler().runTaskLater(plugin, () -> tagCrazyTreasureHeart(owner, quest.questId(), worldName, x, y, z), 1L);
    }

    private void tagCrazyTreasureHeart(UUID owner, String questId, String worldName, int x, int y, int z) {
        Player player = Bukkit.getPlayer(owner);
        HuntQuest quest = huntQuests.get(owner);
        World world = Bukkit.getWorld(worldName);
        if (player == null || quest == null || !quest.questId().equals(questId) || quest.foundTreasure() || world == null) return;
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof org.bukkit.block.Chest chest)
                || !questId.equals(chest.getPersistentDataContainer().get(crazyTreasureChestKey, PersistentDataType.STRING))) return;
        ItemStack heart = Arrays.stream(chest.getBlockInventory().getContents())
                .filter(item -> item != null && item.getType() == Material.HEART_OF_THE_SEA)
                .findFirst().orElse(null);
        if (heart == null) {
            player.sendMessage(plugin.message("&cThis buried treasure chest contains no Heart of the Sea."));
            return;
        }
        ItemMeta heartMeta = heart.getItemMeta();
        if (heartMeta == null) return;
        heartMeta.getPersistentDataContainer().set(crazyTreasureHeartKey, PersistentDataType.STRING, questId);
        heart.setItemMeta(heartMeta);
        chest.update(true, false);
        HuntQuest updated = quest.treasureFound();
        if (!saveHuntQuest(updated)) { saveHuntQuest(quest); return; }
        huntQuests.put(owner, updated);
        player.sendMessage(plugin.message("&aBuried treasure found. Bring its uniquely marked Heart of the Sea to the Crazy Angel."));
    }

    private boolean submitCrazyQuestItem(Player player) {
        HuntQuest quest = huntQuests.get(player.getUniqueId());
        if (quest == null || quest.objective().equals("hunt")) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        boolean main = isCrazySubmission(quest, item);
        if (!main) item = player.getInventory().getItemInOffHand();
        if (!isCrazySubmission(quest, item)) return false;
        ItemMeta submittedMeta = item.getItemMeta();
        if (submittedMeta == null) return false;
        submittedMeta.getPersistentDataContainer().set(crazyConsumedItemKey, PersistentDataType.STRING, quest.questId());
        item.setItemMeta(submittedMeta);
        player.saveData();
        if (!claimCrazyQuest(quest, List.of(randomCrazyReward()), 0L, quest.questId())) {
            player.sendMessage(plugin.message("&cThe quest completion could not be saved; keep the required item and try again."));
            return true;
        }
        finishCrazyQuestRewards(player, quest.questId());
        player.sendMessage(plugin.message("&aCrazy Angel quest complete. Your reward has been granted."));
        return true;
    }

    private boolean isCrazySubmission(HuntQuest quest, ItemStack item) {
        Material expected = quest.itemType().isBlank() ? null : Material.matchMaterial(quest.itemType());
        if (item == null || expected == null || item.getType() != expected) return false;
        if (quest.objective().equals("treasure") && !quest.foundTreasure()) return false;
        if (quest.objective().equals("treasure") && (!item.hasItemMeta() || !quest.questId().equals(
                item.getItemMeta().getPersistentDataContainer().get(crazyTreasureHeartKey, PersistentDataType.STRING)))) return false;
        if (quest.enchantment().isBlank()) return true;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored)) return false;
        NamespacedKey key = NamespacedKey.fromString(quest.enchantment());
        org.bukkit.enchantments.Enchantment enchantment = key == null ? null : org.bukkit.enchantments.Enchantment.getByKey(key);
        return enchantment != null && stored.hasStoredEnchant(enchantment);
    }

    private boolean claimCrazyQuest(HuntQuest quest, List<ItemStack> rewards, long marks, String consumedSubmission) {
        if (state.getBoolean("crazy-claims." + quest.questId() + ".claimed", false)) return false;
        String path = "hunt-quests." + quest.owner();
        state.set(path, null);
        state.set("crazy-claims." + quest.questId() + ".claimed", true);
        state.set("crazy-claims." + quest.questId() + ".owner", quest.owner().toString());
        state.set("crazy-claims." + quest.questId() + ".rewards", rewards);
        state.set("crazy-claims." + quest.questId() + ".marks", Math.max(0L, marks));
        state.set("crazy-claims." + quest.questId() + ".consumed-submission", consumedSubmission);
        state.set("crazy-claims." + quest.questId() + ".delivered", false);
        if (!saveState()) {
            state.set("crazy-claims." + quest.questId(), null);
            saveHuntQuest(quest);
            return false;
        }
        huntQuests.remove(quest.owner());
        return true;
    }

    private void deliverPendingCrazyRewards(Player player) {
        var claims = state.getConfigurationSection("crazy-claims");
        if (claims == null) return;
        for (String id : new ArrayList<>(claims.getKeys(false))) {
            String path = "crazy-claims." + id;
            if (state.getBoolean(path + ".claimed") && !state.getBoolean(path + ".delivered")
                    && player.getUniqueId().toString().equals(state.getString(path + ".owner"))) {
                finishCrazyQuestRewards(player, id);
            }
        }
    }

    private void finishCrazyQuestRewards(Player player, String questId) {
        String path = "crazy-claims." + questId;
        if (state.getBoolean(path + ".delivered", false)
                || !player.getUniqueId().toString().equals(state.getString(path + ".owner"))) return;
        String consumed = state.getString(path + ".consumed-submission", "");
        if (consumed != null && !consumed.isBlank()) {
            for (org.bukkit.inventory.Inventory inventory : List.of(player.getInventory(), player.getEnderChest())) {
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    ItemStack item = inventory.getItem(slot);
                    if (item == null || !item.hasItemMeta()
                            || !consumed.equals(item.getItemMeta().getPersistentDataContainer().get(crazyConsumedItemKey, PersistentDataType.STRING))) continue;
                    if (item.getAmount() == 1) inventory.setItem(slot, null);
                    else {
                        item.setAmount(item.getAmount() - 1);
                        clearTransactionTag(item, crazyConsumedItemKey);
                    }
                    break;
                }
            }
            player.saveData();
        }
        long marks = Math.max(0L, state.getLong(path + ".marks", 0L));
        if (marks > 0 && !plugin.creditOnce(player.getUniqueId(), marks, "Crazy Angel quest reward", "crazy:" + questId + ":marks")) return;
        List<?> storedRewards = state.getList(path + ".rewards");
        if (storedRewards == null || storedRewards.isEmpty()) {
            plugin.getLogger().severe("Pending Crazy quest reward is missing or malformed; leaving claim pending: " + questId);
            return;
        }
        List<ItemStack> rewards = new ArrayList<>(storedRewards.size());
        for (Object storedReward : storedRewards) {
            if (!(storedReward instanceof ItemStack item) || item.getType().isAir()) {
                plugin.getLogger().severe("Pending Crazy quest reward is missing or malformed; leaving claim pending: " + questId);
                return;
            }
            rewards.add(item);
        }
        ItemStack[] before = cloneInventoryItems(player.getInventory().getContents());
        boolean overflowed = false;
        for (int i = 0; i < rewards.size(); i++) {
            String receipt = questId + ":" + i;
            if (hasTransactionItem(player, crazyRewardDeliveryKey, receipt)) continue;
            ItemStack savedReward = rewards.get(i);
            if (savedReward == null || savedReward.getType().isAir()) { overflowed = true; break; }
            ItemStack reward = savedReward.clone();
            ItemMeta meta = reward.getItemMeta();
            if (meta == null) { overflowed = true; break; }
            meta.getPersistentDataContainer().set(crazyRewardDeliveryKey, PersistentDataType.STRING, receipt);
            reward.setItemMeta(meta);
            if (!player.getInventory().addItem(reward).isEmpty()) { overflowed = true; break; }
        }
        if (overflowed) {
            player.getInventory().setContents(before);
            player.sendMessage(plugin.message("&eYour Crazy Angel reward is saved. Make inventory space and close an inventory or reconnect to receive it."));
            return;
        }
        player.saveData();
        state.set(path + ".delivered", true);
        if (!saveState()) {
            state.set(path + ".delivered", false);
            plugin.getLogger().severe("Crazy quest reward items were saved to player data but claim finalization failed: " + questId);
        }
    }

    private ItemStack randomCrazyReward() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 24) { String clazz = randomClass(); return relics.createRelic(clazz, relicAbility(clazz, 2), 2); }
        if (roll < 30) { String clazz = randomClass(); return relics.createRelic(clazz, relicAbility(clazz, 3), 3); }
        if (roll < 43) return new ItemStack(Material.DIAMOND, ThreadLocalRandom.current().nextInt(2, 9));
        if (roll < 57) return new ItemStack(Material.GOLD_INGOT, ThreadLocalRandom.current().nextInt(8, 25));
        if (roll < 66) return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        if (roll < 74) return new ItemStack(Material.TOTEM_OF_UNDYING);
        return createAngelFragment();
    }

    private ItemStack createAngelFragment() {
        ItemStack fragment = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = fragment.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Angel Fragment");
        meta.setLore(List.of(ChatColor.GRAY + "A mysterious relic-crafting material."));
        fragment.setItemMeta(meta);
        return fragment;
    }

    private ItemStack[] cloneInventoryItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
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
        state.set("angel-marked-until", null);
        angelMarkedUntil.forEach((uuid, expires) -> state.set("angel-marked-until." + uuid, expires));
        try {
            SafeYaml.saveAtomically(state, stateFile);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not persist relic encounter state: " + ex.getMessage());
            return false;
        }
    }
}
