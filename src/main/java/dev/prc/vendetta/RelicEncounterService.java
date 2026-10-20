package dev.prc.vendetta;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
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
    private final NamespacedKey villagerKey, questKey, shatterKey, villagerQuestTypeKey;
    private final File stateFile;
    private final YamlConfiguration state;
    private final Map<UUID, Integer> expiryTasks = new HashMap<>();
    private final Map<UUID, Quest> quests = new HashMap<>();
    private final Map<UUID, HuntQuest> huntQuests = new HashMap<>();
    private final Map<UUID, Long> disabledUntil = new HashMap<>();
    private final Set<UUID> swordUseLocks = new HashSet<>();
    private final Set<String> claimedBoxes = new HashSet<>();

    private record Quest(UUID owner, String world, int x, int y, int z, String type, boolean claimed) {
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
        shatterKey = new NamespacedKey(plugin, "shatter_sword");
        stateFile = new File(plugin.getDataFolder(), "relic-encounters.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        claimedBoxes.addAll(state.getStringList("claimed-boxes"));
        for (String key : state.getConfigurationSection("quests") == null
                ? Collections.<String>emptySet() : state.getConfigurationSection("quests").getKeys(false)) {
            String path = "quests." + key;
            try {
                Quest quest = new Quest(UUID.fromString(state.getString(path + ".owner")),
                        state.getString(path + ".world"), state.getInt(path + ".x"), state.getInt(path + ".y"),
                        state.getInt(path + ".z"), state.getString(path + ".type", "nether"), state.getBoolean(path + ".claimed"));
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
        for (String key : state.getConfigurationSection("disabled") == null
                ? Collections.<String>emptySet() : state.getConfigurationSection("disabled").getKeys(false)) {
            try { disabledUntil.put(UUID.fromString(key), state.getLong("disabled." + key)); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    void start() {
        // Start a wave on startup, then repeat at the configured interval (default one hour).
        long interval = Math.max(1, plugin.getConfig().getLong("relic-villagers.interval-minutes", 60)) * 60L * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnWave, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnWave, interval, interval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireOldDisables, 20L * 30, 20L * 30);
    }

    void shutdown() {
        for (Integer task : expiryTasks.values()) Bukkit.getScheduler().cancelTask(task);
        expiryTasks.clear();
        saveState();
    }

    private void spawnWave() {
        cleanupOldVillagers();
        int count = Math.max(1, plugin.getConfig().getInt("relic-villagers.batch-size", 8));
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
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
            // Four Angel variants: Crazy (quest-only), Ascendant and Perfect (traders), Wanderer (private cache quest).
            int angelRoll = ThreadLocalRandom.current().nextInt(100);
            String questType = angelRoll < 25 ? "crazy" : angelRoll < 50 ? "ascendant" : angelRoll < 75 ? "perfect" : "wanderer";
            villager.getPersistentDataContainer().set(villagerQuestTypeKey, PersistentDataType.STRING, questType);
            if (questType.equals("ascendant") || questType.equals("perfect")) {
                villager.setRecipes(createTrades(point.structureName(), questType));
            } else {
                villager.setRecipes(Collections.emptyList());
            }
            long lifetime = Math.max(1, plugin.getConfig().getLong("relic-villagers.lifetime-minutes", 15)) * 60L * 20L;
            int task = Bukkit.getScheduler().runTaskLater(plugin, () -> removeVillager(villager), lifetime).getTaskId();
            expiryTasks.put(villager.getUniqueId(), task);
            spawned++;
        }
        if (spawned > 0) Bukkit.broadcastMessage(plugin.message("&dAngels escaped"));
        plugin.getLogger().info("Spawned " + spawned + " relic villagers for this hourly wave.");
    }

    private void cleanupOldVillagers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Villager villager
                        && villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) {
                    Integer task = expiryTasks.remove(villager.getUniqueId());
                    if (task != null) Bukkit.getScheduler().cancelTask(task);
                    villager.remove();
                }
            }
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

    private List<MerchantRecipe> createTrades(String structureName, String angelType) {
        List<MerchantRecipe> recipes = new ArrayList<>();
        // Each villager receives a fixed offer set once; reopening the trade screen cannot reroll it.
        List<Integer> tiers = new ArrayList<>();
        for (int i = 0; i < 3; i++) tiers.add(angelType.equals("ascendant") ? 3 : 2);
        String[] classes = {"vanguard", "phantom", "warden"};
        for (int i = 0; i < 3; i++) {
            String clazz = classes[ThreadLocalRandom.current().nextInt(classes.length)];
            int tier = tiers.get(i);
            String ability = relicAbility(clazz, tier);
            ItemStack output = relics.createRelic(clazz, ability, tier);
            MerchantRecipe recipe = new MerchantRecipe(output, 0, 1, true);
            recipe.addIngredient(new ItemStack(Material.EMERALD, tier == 1 ? 16 : 32));
            recipe.addIngredient(new ItemStack(Material.DIAMOND, tier == 1 ? 2 : 8));
            recipes.add(recipe);
        }
        if (angelType.equals("ascendant")) {
            ItemStack sword = createShatterSword();
            MerchantRecipe swordRecipe = new MerchantRecipe(sword, 0, 1, true);
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
        meta.setLore(List.of(ChatColor.GRAY + "One successful player hit only",
                ChatColor.GRAY + "Costs 500 Marks on activation",
                ChatColor.GRAY + "Disables one relic ability for 30 minutes"));
        meta.getPersistentDataContainer().set(shatterKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void removeVillager(Villager villager) {
        expiryTasks.remove(villager.getUniqueId());
        if (!villager.isValid()) return;
        villager.getWorld().spawnParticle(Particle.SMOKE, villager.getLocation().add(0, 1, 0), 18, 0.35, 0.6, 0.35, 0.02);
        villager.getWorld().playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_DEATH, 1.0f, 0.75f);
        villager.remove();
        Bukkit.broadcastMessage(plugin.message("&5Escaped angels enchained"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) return;
        String type = villager.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "crazy");
        if (type.equals("crazy")) event.getPlayer().sendMessage(plugin.message("&cCrazy Angel: left-click to receive a timed quest."));
        else if (type.equals("wanderer")) event.getPlayer().sendMessage(plugin.message("&dWanderer Angel: left-click to receive your private cache quest."));
        else event.getPlayer().sendMessage(plugin.message("&eLeft-click for Angel details; right-click to trade."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVillagerLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        String type = villager.getPersistentDataContainer().getOrDefault(villagerQuestTypeKey, PersistentDataType.STRING, "crazy");
        if (type.equals("crazy")) startCrazyQuest(player);
        else if (type.equals("wanderer")) startWandererQuest(player);
        else player.sendMessage(plugin.message(type.equals("ascendant")
                ? "&6Ascendant Angel: trade for high-tier relics by right-clicking me."
                : "&bPerfect Angel: trade for Perfect relics by right-clicking me."));
    }


    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onAngelInvulnerable(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Villager villager
                && villager.getPersistentDataContainer().has(villagerKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWandererCacheInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof org.bukkit.block.ShulkerBox)) return;
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        Quest quest = quests.values().stream().filter(q -> q.key().equals(key) && !q.claimed()).findFirst().orElse(null);
        if (quest == null) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!quest.owner().equals(player.getUniqueId())) {
            player.sendMessage(plugin.message("&cThis angelic cache belongs to another player."));
            return;
        }
        org.bukkit.block.ShulkerBox box = (org.bukkit.block.ShulkerBox) block.getState();
        org.bukkit.inventory.Inventory inventory = box.getInventory();
        boolean hasHeart = Arrays.stream(inventory.getContents()).filter(Objects::nonNull)
                .anyMatch(item -> item.getType() == Material.HEART_OF_THE_SEA);
        if (!hasHeart) {
            player.sendMessage(plugin.message("&dThe Wanderer requires a Heart of the Sea inside this box."));
            return;
        }
        boolean removed = false;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == Material.HEART_OF_THE_SEA) {
                item.setAmount(item.getAmount() - 1);
                if (item.getAmount() <= 0) inventory.setItem(i, null);
                removed = true;
                break;
            }
        }
        if (!removed || !claimedBoxes.add(key)) return;
        Quest completed = new Quest(quest.owner(), quest.world(), quest.x(), quest.y(), quest.z(), quest.type(), true);
        quests.put(quest.owner(), completed);
        saveQuest(completed);
        deliverRewards(player, quest.type());
        block.setType(Material.AIR, false);
        player.sendMessage(plugin.message("&aWanderer quest complete! The Heart of the Sea has been accepted."));
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
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat a Warden within 15 minutes! Reward: 8 diamonds and a Marks bonus."));
        } else if (roll == 1) {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "CREEPER", 5, 0, 5, ThreadLocalRandom.current().nextLong(500, 1251));
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 5 Creepers within 15 minutes! Reward: diamonds and Marks."));
        } else if (roll == 2) {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "SKELETON", 8, 0, 4, 0);
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 8 Skeletons within 15 minutes! Reward: a custom Angel Fragment."));
        } else {
            HuntQuest quest = new HuntQuest(player.getUniqueId(), "PILLAGER", 6, 0, 6, ThreadLocalRandom.current().nextLong(500, 1251));
            huntQuests.put(player.getUniqueId(), quest);
            saveHuntQuest(quest);
            player.sendMessage(plugin.message("&cCrazy Angel: Defeat 6 Pillagers within 15 minutes! Reward: diamonds and Marks."));
        }
        HuntQuest scheduledQuest = huntQuests.get(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            HuntQuest active = huntQuests.get(player.getUniqueId());
            if (active != null && active.equals(scheduledQuest)) {
                huntQuests.remove(player.getUniqueId());
                state.set("hunt-quests." + player.getUniqueId(), null);
                saveState();
                if (player.isOnline()) player.sendMessage(plugin.message("&cCrazy Angel quest expired."));
            }
        }, 15L * 60 * 20);
    }

    private void startWandererQuest(Player player) {
        if (hasActiveQuest(player.getUniqueId())) {
            player.sendMessage(plugin.message("&cFinish your current angelic quest first."));
            return;
        }
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
        Quest quest = new Quest(player.getUniqueId(), world.getName(), chosen.getBlockX(), chosen.getBlockY(), chosen.getBlockZ(), "wanderer", false);
        quests.put(player.getUniqueId(), quest);
        saveQuest(quest);
        player.sendMessage(plugin.message("&dWanderer quest accepted! A private cache spawned within 100 blocks. Place a Heart of the Sea inside the shulker box at &f"
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
        saveState();
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuestBoxBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        Quest quest = quests.values().stream().filter(q -> q.key().equals(key) && !q.claimed()).findFirst().orElse(null);
        if (quest == null) return;
        if (quest.type().equals("wanderer")) { event.setCancelled(true); return; }
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
        return quests.values().stream().anyMatch(quest -> !quest.claimed() && quest.key().equals(key));
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
        if (roll < 94) return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        return new ItemStack(Material.NETHERITE_INGOT, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShatterHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player target)) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!weapon.hasItemMeta() || !weapon.getItemMeta().getPersistentDataContainer().has(shatterKey, PersistentDataType.BYTE)) return;
        if (event.getFinalDamage() <= 0 || swordUseLocks.contains(attacker.getUniqueId())) return;
        if (!plugin.debit(attacker.getUniqueId(), 500)) {
            event.setCancelled(true);
            attacker.sendMessage(plugin.message("&cShatter Sword activation requires 500 Marks."));
            return;
        }
        swordUseLocks.add(attacker.getUniqueId());
        weapon.setAmount(weapon.getAmount() - 1);
        target.getWorld().strikeLightning(target.getLocation());
        long until = System.currentTimeMillis() + 30L * 60 * 1000;
        disabledUntil.put(target.getUniqueId(), until);
        state.set("disabled." + target.getUniqueId(), until);
        saveState();
        attacker.sendMessage(plugin.message("&dShatter Sword consumed. 500 Marks paid."));
        target.sendMessage(plugin.message("&5Your class abilities have been shattered for 30 minutes."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> swordUseLocks.remove(attacker.getUniqueId()), 1L);
    }

    boolean isDisabled(UUID playerId) { return disabledUntil.getOrDefault(playerId, 0L) > System.currentTimeMillis(); }

    private void expireOldDisables() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(disabledUntil.keySet())) {
            if (disabledUntil.get(id) <= now) {
                disabledUntil.remove(id);
                state.set("disabled." + id, null);
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.sendMessage(plugin.message("&aYour class abilities have been restored."));
            }
        }
        saveState();
    }

    private void saveState() {
        state.set("claimed-boxes", new ArrayList<>(claimedBoxes));
        try { state.save(stateFile); }
        catch (IOException ex) { plugin.getLogger().severe("Could not persist relic encounter state: " + ex.getMessage()); }
    }
}
