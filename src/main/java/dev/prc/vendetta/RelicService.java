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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
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
    private final ClassProgression classProgression;
    private final NamespacedKey classKey, abilityKey, tierKey, tierIdKey, relicIdKey, formatKey, priceKey, shopKey, merchantKey;
    private final File exploredFile;
    private final File relicIdentityFile;
    private final YamlConfiguration exploredData;
    private final YamlConfiguration relicIdentityData;
    private final Set<String> explored = new HashSet<>();
    private final Set<String> issuedRelics = new HashSet<>();
    private final Set<String> consumedRelics = new HashSet<>();
    private final Set<java.util.UUID> purchaseLocks = new HashSet<>();
    private final Set<java.util.UUID> abilityMenuLocks = new HashSet<>();
    private final Set<java.util.UUID> relicUseGuard = new HashSet<>();

    RelicService(mark plugin, VanguardProgression progression, ClassProgression classProgression) {
        this.plugin = plugin;
        this.progression = progression;
        this.classProgression = classProgression;
        classKey = new NamespacedKey(plugin, "relic_class");
        abilityKey = new NamespacedKey(plugin, "relic_ability");
        tierKey = new NamespacedKey(plugin, "relic_tier");
        tierIdKey = new NamespacedKey(plugin, "relic_tier_id");
        relicIdKey = new NamespacedKey(plugin, "relic_id");
        formatKey = new NamespacedKey(plugin, "relic_format");
        priceKey = new NamespacedKey(plugin, "relic_price");
        shopKey = new NamespacedKey(plugin, "relic_shop");
        merchantKey = new NamespacedKey(plugin, "relic_merchant");
        exploredFile = new File(plugin.getDataFolder(), "explored-chests.yml");
        exploredData = YamlConfiguration.loadConfiguration(exploredFile);
        explored.addAll(exploredData.getStringList("opened"));
        relicIdentityFile = new File(plugin.getDataFolder(), "relic-identities.yml");
        relicIdentityData = YamlConfiguration.loadConfiguration(relicIdentityFile);
        issuedRelics.addAll(relicIdentityData.getStringList("issued"));
        consumedRelics.addAll(relicIdentityData.getStringList("consumed"));
    }

    @EventHandler(ignoreCancelled = false)
    public void onRelicUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getItem() == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(classKey, PersistentDataType.STRING)) return;
        Player player = event.getPlayer();
        String selectedClass = plugin.selectedClass(player.getUniqueId());
        String ability = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if ("headweb".equals(ability)) ability = "bloodthirst";
        String clazz = meta.getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        int tier = meta.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        EquipmentSlot hand = event.getHand();
        if (!isGenuineRelic(event.getItem(), clazz, ability, tier)) {
            return;
        }
        if (!relicUseGuard.add(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> relicUseGuard.remove(player.getUniqueId()));
        event.setCancelled(true);
        plugin.getLogger().fine("Recognized " + tierName(tier) + " " + clazz + " relic use by " + player.getUniqueId() + " in " + hand);
        if (!clazz.equals(selectedClass)) { player.sendMessage(plugin.message("&cThis relic does not match your class.")); return; }
        if (!List.of("vanguard", "phantom", "warden").contains(clazz)) return;
        String classUltimate = "vanguard".equals(clazz) ? "ultimate" : "class_relic";
        if (tier == 3) {
            if (!List.of(classUltimate, abilityKey(clazz, "ultimate"), "class_relic").contains(ability)) { player.sendMessage(plugin.message("&cAscendant relics only upgrade the ultimate.")); return; }
            applyRelicUpgrade(player, hand, "ultimate", 3);
        } else if (tier == 1) {
            if (!List.of("select", "class_relic", "berserker", "bloodthirst", "predators_instinct", "marked_prey", "unshaken", "sculk_pulse").contains(ability)) {
                player.sendMessage(plugin.message("&cUnknown ability on this relic.")); return;
            }
            openAbilityMenu(player, hand, tier, clazz);
        } else if (tier == 2) {
            if (List.of(classUltimate, abilityKey(clazz, "ultimate"), "select", "class_relic").contains(ability)
                    || isRegularAbility(clazz, ability)) {
                openAbilityMenu(player, hand, tier, clazz);
            } else player.sendMessage(plugin.message("&cUnknown ability on this relic."));
        } else player.sendMessage(plugin.message("&cInvalid relic tier."));
    }

    private boolean isGenuineRelic(ItemStack item, String clazz, String ability, int tier) {
        if (item == null || item.getAmount() != 1 || !item.hasItemMeta() || clazz == null || ability == null) return false;
        if (!List.of("vanguard", "phantom", "warden").contains(clazz) || tier < 1 || tier > 3) return false;
        if (!validRelicDefinition(clazz, ability, tier)) return false;
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        if (!RelicItemContract.valid(item.getType(), tier,
                pdc.get(relicIdKey, PersistentDataType.STRING),
                pdc.get(tierIdKey, PersistentDataType.STRING),
                pdc.getOrDefault(formatKey, PersistentDataType.INTEGER, 0))) return false;
        String relicId = pdc.get(relicIdKey, PersistentDataType.STRING);
        if (!issuedRelics.contains(relicId) || consumedRelics.contains(relicId)) return false;
        return true;
    }

    boolean validRelicDefinition(String clazz, String ability, int tier) {
        if (!List.of("vanguard", "phantom", "warden").contains(clazz) || ability == null || tier < 1 || tier > 3) return false;
        return switch (clazz) {
            case "vanguard" -> tier == 1
                    ? List.of("select", "berserker", "bloodthirst").contains(ability)
                    : List.of("select", "ultimate", "berserker", "bloodthirst").contains(ability);
            case "phantom" -> "class_relic".equals(ability)
                    || tier == 1 && List.of("predators_instinct", "marked_prey").contains(ability)
                    || tier == 2 && List.of("predators_instinct", "marked_prey", "into_the_void").contains(ability)
                    || tier == 3 && "into_the_void".equals(ability);
            case "warden" -> "class_relic".equals(ability)
                    || tier == 1 && List.of("unshaken", "sculk_pulse").contains(ability)
                    || tier == 2 && List.of("unshaken", "sculk_pulse", "ancient_guardian").contains(ability)
                    || tier == 3 && "ancient_guardian".equals(ability);
            default -> false;
        };
    }

    private boolean isRegularAbility(String clazz, String ability) {
        return switch (clazz) {
            case "vanguard" -> List.of("berserker", "bloodthirst").contains(ability);
            case "phantom" -> List.of("predators_instinct", "marked_prey").contains(ability);
            case "warden" -> List.of("unshaken", "sculk_pulse").contains(ability);
            default -> false;
        };
    }

    private void openAbilityMenu(Player player, EquipmentSlot hand, int relicTier, String clazz) {
        if (!abilityMenuLocks.add(player.getUniqueId())) return;
        Inventory inventory = Bukkit.createInventory(new AbilityMenuHolder(player.getUniqueId(), hand, relicTier, clazz), 27,
                ChatColor.DARK_RED + "Choose a " + capitalize(clazz) + " Ability");
        AbilityMenuHolder holder = (AbilityMenuHolder) inventory.getHolder();
        holder.inventory = inventory;
        if ("vanguard".equals(clazz)) {
            VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
            putAbility(inventory, 11, Material.IRON_SWORD, "Berserker", "Low-health outgoing damage bonus", data.berserker, relicTier);
            putAbility(inventory, 15, Material.SHIELD, "Bloodthirst", "Sword counter that heals on a successful counter", data.bloodthirst, relicTier);
            if (relicTier == 2) putAbility(inventory, 13, Material.TOTEM_OF_UNDYING, "BLOOD BLOOD BLOOD", "Vanguard combat ultimate", data.ultimate, relicTier);
        } else if ("phantom".equals(clazz)) {
            putAbility(inventory, 11, Material.IRON_SWORD, "Predator's Instinct", "Timed sword counter", classProgression.tier(player.getUniqueId(), "predators_instinct"), relicTier);
            putAbility(inventory, 15, Material.ENDER_EYE, "Marked Prey", "Glowing when you are attacked", classProgression.tier(player.getUniqueId(), "marked_prey"), relicTier);
            if (relicTier == 2) putAbility(inventory, 13, Material.ENDER_PEARL, "Into the Void", "Phantom stealth ultimate", classProgression.tier(player.getUniqueId(), "into_the_void"), relicTier);
        } else {
            putAbility(inventory, 11, Material.NETHERITE_CHESTPLATE, "Unshaken", "Knockback resistance", classProgression.tier(player.getUniqueId(), "unshaken"), relicTier);
            putAbility(inventory, 15, Material.SCULK_SENSOR, "Sculk Pulse", "Reveal nearby enemy players", classProgression.tier(player.getUniqueId(), "sculk_pulse"), relicTier);
            if (relicTier == 2) putAbility(inventory, 13, Material.SHIELD, "Ancient Guardian", "Warden defensive ultimate", classProgression.tier(player.getUniqueId(), "ancient_guardian"), relicTier);
        }
        player.openInventory(inventory);
    }

    private void putAbility(Inventory inventory, int slot, Material material, String name, String description,
                            VanguardProgression.Upgrade current, int relicTier) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(List.of(ChatColor.GRAY + description, ChatColor.GRAY + "Current upgrade: " + current,
                ChatColor.LIGHT_PURPLE + "Applying: " + tierName(relicTier) + " relic"));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler(ignoreCancelled = false)
    public void onAbilityMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AbilityMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.playerId) || holder.resolved) return;
        String ability = switch (event.getRawSlot()) {
            case 11 -> holder.className.equals("vanguard") ? "berserker" : holder.className.equals("phantom") ? "predators_instinct" : "unshaken";
            case 15 -> holder.className.equals("vanguard") ? "bloodthirst" : holder.className.equals("phantom") ? "marked_prey" : "sculk_pulse";
            case 13 -> holder.relicTier == 2 ? "ultimate" : null;
            default -> null;
        };
        if (ability == null) { player.sendMessage(plugin.message("&cSelect an available ability from the menu.")); return; }
        if (applyRelicUpgrade(player, holder.hand, ability, holder.relicTier)) {
            holder.resolved = true;
            player.closeInventory();
            abilityMenuLocks.remove(player.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onAbilityMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AbilityMenuHolder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = false)
    public void onAbilityMenuHeldSlot(PlayerItemHeldEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof AbilityMenuHolder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = false)
    public void onAbilityMenuHandSwap(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof AbilityMenuHolder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = false)
    public void onAbilityMenuDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof AbilityMenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onAbilityMenuClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof AbilityMenuHolder holder && !holder.resolved)
            abilityMenuLocks.remove(holder.playerId);
    }

    private boolean applyRelicUpgrade(Player player, EquipmentSlot hand, String ability, int tier) {
        ItemStack relic = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        ItemMeta meta = relic == null ? null : relic.getItemMeta();
        String clazz = meta == null ? null : meta.getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        String storedAbility = meta == null ? null : meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        int storedTier = meta == null ? 0 : meta.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        if (!isGenuineRelic(relic, clazz, storedAbility, storedTier)
                || !clazz.equals(plugin.selectedClass(player.getUniqueId())) || storedTier != tier
                || (tier == 3 && !"ultimate".equals(ability))
                || !(storedAbility.equals(ability) || storedAbility.equals("class_relic") || storedAbility.equals("select")
                || ("ultimate".equals(ability) && storedAbility.equals(abilityKey(clazz, "ultimate"))))) {
            player.sendMessage(plugin.message("&cThe relic is no longer valid or does not match this upgrade.")); return false;
        }
        boolean vanguardClass = "vanguard".equals(clazz);
        VanguardProgression.PlayerData data = vanguardClass ? progression.get(player.getUniqueId()) : null;
        VanguardProgression.Upgrade current = vanguardClass ? switch (ability) {
            case "berserker" -> data.berserker;
            case "bloodthirst" -> data.bloodthirst;
            case "ultimate" -> data.ultimate;
            default -> null;
        } : classProgression.tier(player.getUniqueId(), abilityKey(clazz, ability));
        if (current == null) { player.sendMessage(plugin.message("&cUnknown ability.")); return false; }
        boolean ultimate = "ultimate".equals(ability);
        VanguardProgression.Upgrade next = RelicProgressionRules.next(current, tier, ultimate);
        if (next == null) {
            player.sendMessage(plugin.message(tier == 3 ? "&cUpgrade the ultimate to Perfect before applying an Ascendant relic."
                    : tier == 2 && ultimate ? "&cThat ultimate cannot receive a Perfect relic at its current progression."
                    : tier == 2 ? "&cThat regular ability cannot receive a Perfect relic at its current progression."
                    : "&cThat regular ability is already upgraded or cannot receive this relic."));
            return false;
        }

        setCurrentUpgrade(player, data, clazz, ability, next);
        try { if (vanguardClass) progression.save(player.getUniqueId()); else classProgression.save(player.getUniqueId()); }
        catch (RuntimeException ex) {
            setCurrentUpgrade(player, data, clazz, ability, current);
            try { if (vanguardClass) progression.save(player.getUniqueId()); else classProgression.save(player.getUniqueId()); } catch (RuntimeException rollback) { ex.addSuppressed(rollback); }
            plugin.getLogger().severe("Could not save relic upgrade for " + player.getUniqueId() + ": " + ex.getMessage());
            player.sendMessage(plugin.message("&cUpgrade could not be saved; the relic was not consumed."));
            return false;
        }
        String relicId = meta.getPersistentDataContainer().get(relicIdKey, PersistentDataType.STRING);
        if (!consumeRelicId(relicId)) {
            setCurrentUpgrade(player, data, clazz, ability, current);
            try { if (vanguardClass) progression.save(player.getUniqueId()); else classProgression.save(player.getUniqueId()); }
            catch (RuntimeException rollback) { plugin.getLogger().severe("Relic transaction rollback failed: " + rollback.getMessage()); }
            player.sendMessage(plugin.message("&cUpgrade could not commit; the relic was not consumed."));
            return false;
        }
        if (relic.getAmount() <= 1) relic = null;
        else relic.setAmount(relic.getAmount() - 1);
        if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(relic);
        else player.getInventory().setItemInMainHand(relic);
        player.sendMessage(plugin.message("&a" + capitalize(ability) + " upgraded to " + next + "!"));
        plugin.applyClassAbilities(player);
        return true;
    }

    private void setUpgrade(VanguardProgression.PlayerData data, String ability, VanguardProgression.Upgrade upgrade) {
        switch (ability) {
            case "berserker" -> data.berserker = upgrade;
            case "bloodthirst" -> data.bloodthirst = upgrade;
            case "ultimate" -> data.ultimate = upgrade;
        }
    }

    private void setCurrentUpgrade(Player player, VanguardProgression.PlayerData data, String clazz, String ability, VanguardProgression.Upgrade upgrade) {
        if ("vanguard".equals(clazz)) setUpgrade(data, ability, upgrade);
        else classProgression.tier(player.getUniqueId(), abilityKey(clazz, ability), upgrade);
    }

    private String abilityKey(String clazz, String ability) {
        if (!"ultimate".equals(ability)) return ability;
        return "phantom".equals(clazz) ? "into_the_void" : "ancient_guardian";
    }

    private String tierName(int tier) { return switch (tier) { case 1 -> "Imperfect"; case 2 -> "Perfect"; case 3 -> "Ascendant"; default -> "Invalid"; }; }

    @EventHandler
    public void onNaturalClericSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (villager.isValid() && villager.getProfession() == Villager.Profession.CLERIC)
                villager.getPersistentDataContainer().set(merchantKey, PersistentDataType.BYTE, (byte) 1);
        }, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCleric(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager) || villager.getProfession() != Villager.Profession.CLERIC
                || !villager.getPersistentDataContainer().has(merchantKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Inventory inventory = Bukkit.createInventory(new RelicShopHolder(), 27, ChatColor.DARK_PURPLE + "Cleric Relics");
        RelicShopHolder holder = (RelicShopHolder) inventory.getHolder();
        holder.inventory = inventory;
        String[] classes = {"vanguard", "warden", "phantom"};
        int[] slots = {10, 11, 12, 14, 15, 16, 19, 20, 21};
        int offerIndex = 0;
        for (int tier = 1; tier <= 3; tier++) for (String relicClass : classes) {
            int slot = slots[offerIndex++];
            // The designated merchant always exposes a stable 3x3 class/tier matrix.
            // In particular, every Master cleric has all three Ascendant class offers.
            String ability = merchantAbility(relicClass, tier);
            long price = tier == 1 ? 1000 : 2000;
            ItemStack item = createRelic(relicClass, ability, tier);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(priceKey, PersistentDataType.LONG, price);
            meta.getPersistentDataContainer().set(shopKey, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GOLD + (tier == 1 ? "1 genuine VenMark Nether Star (1,000 Marks)" : tier == 2 ? "2 genuine VenMark Nether Stars (2,000 Marks)" : "2 genuine VenMark Nether Stars (2,000 Marks) + " + requiredNetherite(relicClass)));
            lore.add(ChatColor.GRAY + "Class: " + capitalize(relicClass) + " | Ability: " + abilityDescription(relicClass, ability));
            lore.add(ChatColor.YELLOW + "Click to purchase");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
        event.getPlayer().openInventory(inventory);
    }

    private String merchantAbility(String relicClass, int tier) {
        if (tier == 3 && "vanguard".equals(relicClass)) return "ultimate";
        return "vanguard".equals(relicClass) ? "select" : "class_relic";
    }

    @EventHandler(ignoreCancelled = true)
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RelicShopHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (!purchaseLocks.add(player.getUniqueId())) return;
        try {
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(shopKey, PersistentDataType.BYTE)) return;
        int tier = meta.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        String relicClass = meta.getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        String ability = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (relicClass == null || ability == null || !isGenuineRelic(item, relicClass, ability, tier)
                || item.getAmount() != 1 || !relicClass.equals(plugin.selectedClass(player.getUniqueId()))
                || !eligible(player, ability, tier)) {
            player.sendMessage(plugin.message("&cThis relic does not match your class or current progression.")); return;
        }
        Material extra = tier == 3 ? requiredMaterial(relicClass) : null;
        if (tier < 1 || tier > 3 || (tier == 3 && extra == null)) return;
        ItemStack relic = item.clone();
        ItemMeta relicMeta = relic.getItemMeta();
        relicMeta.getPersistentDataContainer().remove(priceKey);
        relicMeta.getPersistentDataContainer().remove(shopKey);
        relicMeta.setLore(List.of(ChatColor.GRAY + "Class: " + capitalize(relicClass), ChatColor.GRAY + "Ability: " + capitalize(ability), ChatColor.GRAY + "Right-click to apply"));
        relic.setItemMeta(relicMeta);
        if (!plugin.purchaseRelic(player, tier, extra, relic)) {
            player.sendMessage(plugin.message(tier == 3
                    ? "&cPurchase failed. Requires two genuine VenMark Nether Stars, the class Netherite item, and enough inventory space; nothing was charged."
                    : "&cPurchase failed. Requires " + (tier == 1 ? "one" : "two") + " genuine VenMark Nether Star(s) and enough inventory space; nothing was charged."));
            return;
        }
        event.getView().getTopInventory().setItem(event.getRawSlot(), null);
        player.sendMessage(plugin.message(tier == 3
                ? "&aAscendant relic purchased. Paid two genuine 1,000-Mark Nether Stars and " + extra.name().replace('_', ' ') + "."
                : "&aRelic purchased using " + (tier == 1 ? "one genuine 1,000-Mark Nether Star." : "two genuine 1,000-Mark Nether Stars.")));
        } finally {
            purchaseLocks.remove(player.getUniqueId());
        }
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
        String ability = tier == 2 ? "ultimate" : "select";
        ItemStack relic = createRelic(ability, tier);
        var overflow = event.getPlayer().getInventory().addItem(relic);
        overflow.values().forEach(stack -> event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), stack));
        event.getPlayer().sendMessage(plugin.message("&dYou discovered a Vanguard relic in the container!"));
    }

    ItemStack createRelic(String ability, int tier) {
        return createRelic("vanguard", ability, tier);
    }

    ItemStack createRelic(String relicClass, String ability, int tier) {
        if (tier < 1 || tier > 3 || !validRelicDefinition(relicClass, ability, tier))
            throw new IllegalArgumentException("Invalid relic definition: " + relicClass + "/" + ability + "/" + tier);
        Material material = RelicItemContract.material(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        String title = tier == 3 ? "Ascendant" : tier == 2 ? "Perfect" : "Imperfect";
        String abilityLabel = "select".equals(ability) ? "Ability Choice" : capitalize(ability);
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + title + " " + capitalize(relicClass) + " " + abilityLabel + " Relic");
        meta.setLore(List.of(ChatColor.GRAY + "Class: " + capitalize(relicClass),
                ChatColor.GRAY + "Ability: " + ("select".equals(ability) ? "Choose when used" : abilityLabel), ChatColor.GRAY + "Tier: " + tier));
        meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, relicClass.toLowerCase(Locale.ROOT));
        meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, ability.toLowerCase(Locale.ROOT));
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(tierIdKey, PersistentDataType.STRING, RelicItemContract.tierId(tier));
        String relicId = java.util.UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(relicIdKey, PersistentDataType.STRING, relicId);
        meta.getPersistentDataContainer().set(formatKey, PersistentDataType.INTEGER, RelicItemContract.FORMAT_VERSION);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        issueRelicId(relicId);
        return item;
    }

    private void issueRelicId(String id) {
        if (!issuedRelics.add(id)) throw new IllegalStateException("Duplicate generated relic identifier");
        if (!saveRelicIdentityData()) {
            issuedRelics.remove(id);
            throw new IllegalStateException("Could not persist genuine relic identity");
        }
    }

    private boolean consumeRelicId(String id) {
        if (id == null || !issuedRelics.contains(id) || !consumedRelics.add(id)) return false;
        if (saveRelicIdentityData()) return true;
        consumedRelics.remove(id);
        return false;
    }

    private boolean saveRelicIdentityData() {
        relicIdentityData.set("format-version", 1);
        relicIdentityData.set("issued", List.copyOf(issuedRelics));
        relicIdentityData.set("consumed", List.copyOf(consumedRelics));
        try { relicIdentityData.save(relicIdentityFile); return true; }
        catch (IOException ex) { plugin.getLogger().severe("Could not save relic identity ledger: " + ex.getMessage()); return false; }
    }


    private String requiredNetherite(String relicClass) {
        return switch (relicClass.toLowerCase(Locale.ROOT)) {
            case "vanguard" -> "1 Netherite Sword";
            case "phantom" -> "1 Netherite Boots";
            case "warden" -> "1 Netherite Chestplate";
            default -> "a class-specific Netherite item";
        };
    }
    private Material requiredMaterial(String relicClass) {
        return switch (relicClass.toLowerCase(Locale.ROOT)) {
            case "vanguard" -> Material.NETHERITE_SWORD;
            case "phantom" -> Material.NETHERITE_BOOTS;
            case "warden" -> Material.NETHERITE_CHESTPLATE;
            default -> null;
        };
    }
    private boolean eligible(Player player, String ability, int tier) {
        String clazz = plugin.selectedClass(player.getUniqueId());
        if (clazz == null) return false;
        if (!"vanguard".equals(clazz)) {
            if (tier == 1 && "class_relic".equals(ability)) {
                String[] regular = clazz.equals("phantom") ? new String[]{"predators_instinct", "marked_prey"} : new String[]{"unshaken", "sculk_pulse"};
                for (String key : regular) if (canUpgrade(classProgression.tier(player.getUniqueId(), key), tier, false)) return true;
                return false;
            }
            String ultimate = abilityKey(clazz, "ultimate");
            VanguardProgression.Upgrade current = classProgression.tier(player.getUniqueId(), ultimate);
            if (tier == 2 && "class_relic".equals(ability)) {
                String[] regular = clazz.equals("phantom") ? new String[]{"predators_instinct", "marked_prey"} : new String[]{"unshaken", "sculk_pulse"};
                for (String key : regular) if (canUpgrade(classProgression.tier(player.getUniqueId(), key), tier, false)) return true;
                return canUpgrade(current, tier, true);
            }
            return tier == 3 && "class_relic".equals(ability) && canUpgrade(current, tier, true);
        }
        VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
        if (tier == 1 && "select".equals(ability))
            return canUpgrade(data.berserker, tier, false) || canUpgrade(data.bloodthirst, tier, false);
        if (tier == 2 && "select".equals(ability))
            return canUpgrade(data.berserker, tier, false)
                    || canUpgrade(data.bloodthirst, tier, false)
                    || canUpgrade(data.ultimate, tier, true);
        VanguardProgression.Upgrade current = switch (ability) {
            case "berserker" -> data.berserker;
            case "bloodthirst", "headweb" -> data.bloodthirst;
            case "ultimate" -> data.ultimate;
            default -> null;
        };
        if (current == null) return false;
        return canUpgrade(current, tier, "ultimate".equals(ability));
    }

    private boolean canUpgrade(VanguardProgression.Upgrade current, int tier, boolean ultimate) {
        return RelicProgressionRules.next(current, tier, ultimate) != null;
    }
    private String abilityDescription(String relicClass, String ability) {
        if (!"vanguard".equalsIgnoreCase(relicClass)) return "Class ability upgrade";
        return switch (ability.toLowerCase(Locale.ROOT)) {
            case "berserker" -> "Low-health damage bonus";
            case "bloodthirst" -> "Sword counter and healing";
            case "ultimate" -> "BLOOD BLOOD BLOOD ultimate";
            default -> "Vanguard ability upgrade";
        };
    }
    private String randomAbility() { return ThreadLocalRandom.current().nextBoolean() ? "berserker" : "bloodthirst"; }
    private String capitalize(String text) { return Character.toUpperCase(text.charAt(0)) + text.substring(1); }
    private static final class AbilityMenuHolder implements InventoryHolder {
        private final java.util.UUID playerId;
        private final EquipmentSlot hand;
        private final int relicTier;
        private final String className;
        private Inventory inventory;
        private boolean resolved;
        private AbilityMenuHolder(java.util.UUID playerId, EquipmentSlot hand, int relicTier, String className) {
            this.playerId = playerId;
            this.hand = hand;
            this.relicTier = relicTier;
            this.className = className;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class RelicShopHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
