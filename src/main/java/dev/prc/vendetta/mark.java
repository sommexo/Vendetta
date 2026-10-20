package dev.prc.vendetta;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import org.bukkit.inventory.PlayerInventory;

public final class mark extends JavaPlugin implements Listener {
    static final long MAX_BANK = 20_000L;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, Long> recentKills = new HashMap<>();
    private final Set<UUID> deathEventsProcessed = new HashSet<>();
    private final Set<UUID> pvpSettlementSuppressed = new HashSet<>();
    private final Set<UUID> rightClickDepositGuard = new HashSet<>();
    private File balanceFile;
    private YamlConfiguration balanceData;
    private File killFile;
    private YamlConfiguration killData;
    private File bountyFile;
    private YamlConfiguration bountyData;
    private File allyFile;
    private YamlConfiguration allyData;
    private final Map<UUID, Set<UUID>> allies = new HashMap<>();
    private VanguardProgression vanguardProgression;
    private ClassProgression classProgression;
    private VanguardAbilityListener vanguardAbilities;
    private CurrencyService currency;
    private ClassSelectionService classSelection;
    private ClassInventoryRules classInventoryRules;
    private ClassAbilityListener classAbilities;
    private WardenUltimateService wardenUltimates;
    private CombatLogService combatLogs;
    private RelicEncounterService relicEncounters;
    private File pendingFile, dailyFile, abuseFile;
    private YamlConfiguration pendingData, dailyData;
    private final List<String> abuseLog = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IllegalStateException("Could not create VenMark data folder");
        balanceFile = new File(getDataFolder(), "balances.yml");
        balanceData = SafeYaml.load(balanceFile);
        for (String key : balanceData.getKeys(false)) {
            if (key.equals("transactions")) continue;
            try {
                balances.put(UUID.fromString(key), balanceData.getLong(key));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid UUID in balances.yml: " + key);
            }
        }
        pendingFile = new File(getDataFolder(), "pending-marks.yml");
        pendingData = SafeYaml.load(pendingFile);
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(balances.entrySet())) {
            if (entry.getValue() <= MAX_BANK) continue;
            long overflow = entry.getValue() - MAX_BANK;
            balances.put(entry.getKey(), MAX_BANK);
            balanceData.set(entry.getKey().toString(), MAX_BANK);
            pendingData.set(entry.getKey().toString(), safeAdd(pendingData.getLong(entry.getKey().toString()), overflow));
        }
        saveBalances();
        savePending();
        dailyFile = new File(getDataFolder(), "daily-pvp.yml");
        dailyData = SafeYaml.load(dailyFile);
        abuseFile = new File(getDataFolder(), "economy-abuse.log");
        if (abuseFile.exists()) try { abuseLog.addAll(java.nio.file.Files.readAllLines(abuseFile.toPath())); } catch (IOException ex) { getLogger().warning("Could not read abuse log: " + ex.getMessage()); }
        killFile = new File(getDataFolder(), "kill-cooldowns.yml");
        killData = SafeYaml.load(killFile);
        for (String key : killData.getKeys(false)) recentKills.put(key, killData.getLong(key));
        bountyFile = new File(getDataFolder(), "bounties.yml");
        bountyData = SafeYaml.load(bountyFile);
        allyFile = new File(getDataFolder(), "allies.yml");
        allyData = SafeYaml.load(allyFile);
        for (String owner : allyData.getKeys(false)) {
            try {
                Set<UUID> entries = new HashSet<>();
                for (String id : allyData.getStringList(owner)) try { entries.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
                allies.put(UUID.fromString(owner), entries);
            } catch (IllegalArgumentException ignored) { }
        }
        vanguardProgression = new VanguardProgression(getDataFolder());
        classProgression = new ClassProgression(getDataFolder());
        currency = new CurrencyService(this);
        classSelection = new ClassSelectionService(this, vanguardProgression);
        classInventoryRules = new ClassInventoryRules(this);
        classAbilities = new ClassAbilityListener(this, classProgression);
        wardenUltimates = new WardenUltimateService(this, classProgression);
        combatLogs = new CombatLogService(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::saveBalances, 20L * 60 * 10, 20L * 60 * 10);
        getServer().getPluginManager().registerEvents(classSelection, this);
        getServer().getPluginManager().registerEvents(classInventoryRules, this);
        getServer().getPluginManager().registerEvents(classAbilities, this);
        getServer().getPluginManager().registerEvents(wardenUltimates, this);
        getServer().getPluginManager().registerEvents(combatLogs, this);
        vanguardAbilities = new VanguardAbilityListener(this, vanguardProgression);
        getServer().getPluginManager().registerEvents(vanguardAbilities, this);
        getServer().getPluginManager().registerEvents(new VanguardActivationHandler(this, vanguardProgression, vanguardAbilities), this);
        RelicService relicService = new RelicService(this, vanguardProgression, classProgression);
        getServer().getPluginManager().registerEvents(relicService, this);
        relicEncounters = new RelicEncounterService(this, relicService);
        getServer().getPluginManager().registerEvents(relicEncounters, this);
        relicEncounters.start();
        registerPermission("venmark.use", PermissionDefault.TRUE, "Use VenMark player commands");
        registerPermission("venmark.admin", PermissionDefault.OP, "Manage VenMark economy data");
        registerPermission("venmark.marks.give", PermissionDefault.OP, "Grant banked Marks");
        registerPermission("venmark.relic.give", PermissionDefault.OP, "Give genuine VenMark relics and currency");
        registerPermission("venmark.class.admin", PermissionDefault.OP, "Override a player's locked class");
        registerPermission("venmark.marks.reset", PermissionDefault.OP, "Reset a confirmed offender's Marks balance");
        registerCommand("marks", "View and manage Marks balances", new EconomyCommands(this, "marks"));
        registerCommand("mark", "View or post player bounties", new EconomyCommands(this, "mark"));
        registerCommand("vanguard", "Choose Vanguard and inspect your abilities", new VanguardCommands(this, vanguardProgression));
        registerCommand("venmark", "Choose a class, view abilities, and manage VenMark economy", new VenMarkCommands(this, classSelection, relicService, vanguardProgression));
        registerCommand("vendetta", "Manage Vendetta server settings", new VendettaCommands(this));
        registerCommand("deposit", "Deposit genuine physical Marks into your bank", new PhysicalEconomyCommands(this));
        registerCommand("withdraw", "Withdraw banked Marks as genuine currency", new PhysicalEconomyCommands(this));
        registerCommand("bounty", "Post a Marks bounty using physical currency", new PhysicalEconomyCommands(this));
        getLogger().info("Loaded " + balances.size() + " Marks balances.");
    }

    @Override
    public void onDisable() {
        if (vanguardAbilities != null) vanguardAbilities.shutdown();
        if (classAbilities != null) classAbilities.shutdown();
        if (wardenUltimates != null) wardenUltimates.shutdown();
        if (combatLogs != null) combatLogs.shutdown();
        if (relicEncounters != null) relicEncounters.shutdown();
        saveBalances();
        saveKillCooldowns();
        saveBounties();
        savePending();
        saveDaily();
        if (classSelection != null) classSelection.save();
        saveAllies();
        if (vanguardProgression != null) vanguardProgression.saveAll();
        if (classProgression != null) classProgression.saveAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        balances.putIfAbsent(event.getPlayer().getUniqueId(), 0L);
        showBalance(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Balance mutations are persisted transactionally; this also flushes the current in-memory snapshot.
        saveBalances();
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = qualifiedAttacker(entity);
        if (killer == null || entity instanceof Player) return;

        long reward = mobReward(entity.getType());
        if (reward <= 0) return;
        credit(killer.getUniqueId(), reward, "mob reward: " + entity.getType());
        killer.sendMessage(color("&a+" + reward + " Marks &7for defeating a " + prettyName(entity.getType())
                + ". Balance: &e" + balance(killer.getUniqueId())));
    }

    private Player qualifiedAttacker(LivingEntity victim) {
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hit)) return null;
        Entity damager = hit.getDamager();
        Player attacker = damager instanceof Player p ? p
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        return attacker == victim ? null : attacker;
    }

    private long mobReward(EntityType type) {
        if (getConfig().getStringList("rewards.boss-mobs").contains(type.name()))
            return getConfig().getLong("rewards.boss", 300);
        if (getConfig().getStringList("rewards.passive-mobs").contains(type.name()))
            return getConfig().getLong("rewards.passive", 5);
        if (getConfig().getStringList("rewards.hostile-mobs").contains(type.name()))
            return getConfig().getLong("rewards.hostile", 10);
        return 0;
    }

    @EventHandler
    public void onPlayerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        // A malformed or repeated death dispatch must settle this death only once.
        // The marker is cleared on respawn so later deaths are handled normally.
        if (!deathEventsProcessed.add(victim.getUniqueId())) return;
        if (event instanceof PlayerDeathEvent playerDeath && qualifiedMobAttacker(victim) != null)
            applyMobCurrencyPenalty(playerDeath, victim);
        if (qualifiedMobAttacker(victim) != null) return;
        if (pvpSettlementSuppressed.contains(victim.getUniqueId())) {
            getServer().getScheduler().runTask(this, () -> pvpSettlementSuppressed.remove(victim.getUniqueId()));
            return;
        }
        UUID killerId = combatLogs == null ? null : combatLogs.killerFor(victim);
        if (killerId == null || killerId.equals(victim.getUniqueId())) return;
        if (!qualifyingPvpFinalDamage(victim, killerId)) return;
        Player killer = getServer().getPlayer(killerId);
        settlePvpDeath(victim, killerId, killer);
    }

    void resolveCombatLog(Player victim, UUID killerId) {
        if (killerId == null || killerId.equals(victim.getUniqueId())) return;
        suppressNextPvpSettlement(victim.getUniqueId());
        settlePvpDeath(victim, killerId, getServer().getPlayer(killerId));
        getServer().getScheduler().runTaskLater(this,
                () -> pvpSettlementSuppressed.remove(victim.getUniqueId()), 2L);
    }

    private void settlePvpDeath(Player victim, UUID killerId, Player killer) {
        payBounty(victim, killer);

        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, getConfig().getLong("pvp.repeat-kill-cooldown-seconds", 600)) * 1000L;
        String firstId = victim.getUniqueId().toString();
        String secondId = killerId.toString();
        String pair = firstId.compareTo(secondId) < 0 ? firstId + ":" + secondId : secondId + ":" + firstId;
        Long previous = recentKills.get(pair);
        if (previous != null && now - previous < cooldown) {
            recordAbuse("repeat-pair blocked killer=" + secondId + " victim=" + firstId + " time=" + now + " reward=0");
            if (killer != null) killer.sendMessage(color("&cNo Marks awarded: repeated kills are blocked for "
                    + Math.max(1, (cooldown - (now - previous) + 999) / 1000) + " more seconds."));
            return;
        }
        recentKills.put(pair, now);
        killData.set(pair, now);
        saveKillCooldowns();

        String localDay = LocalDate.now(ZoneId.systemDefault()).toString();
        String dailyPath = killerId + ".";
        if (!localDay.equals(dailyData.getString(dailyPath + "date"))) {
            dailyData.set(dailyPath + "date", localDay);
            dailyData.set(dailyPath + "used", 0);
        }
        String previousDay = dailyData.getString(dailyPath + "date");
        int previousUsage = dailyData.getInt(dailyPath + "used");
        int used = dailyData.getInt(dailyPath + "used");
        if (used >= 3) {
            recordAbuse("daily-limit killer=" + secondId + " victim=" + firstId + " time=" + now + " used=" + used);
            if (killer != null) killer.sendMessage(color("&cDaily PvP reward limit reached (3/3)."));
            return;
        }
        dailyData.set(dailyPath + "used", used + 1);
        if (!saveDaily()) {
            dailyData.set(dailyPath + "date", previousDay);
            dailyData.set(dailyPath + "used", previousUsage);
            return;
        }
        long victimBalance = balance(victim.getUniqueId());
        long calculatedLoss = Math.max(0L, victimBalance / 4); // floor to whole Marks; Wanderer debt is not compounded.
        long transfer = Math.min(calculatedLoss, 2500L);
        long reward = Math.max(0L, getConfig().getLong("pvp.base-reward", 500));
        if (calculatedLoss > 0) set(victim.getUniqueId(), victimBalance - calculatedLoss);
        credit(killerId, transfer, "PvP bank transfer from " + victim.getUniqueId());
        if (calculatedLoss > transfer) recordAbuse("pvp-transfer-cap sink=" + (calculatedLoss - transfer) + " killer=" + secondId + " victim=" + firstId + " time=" + now);
        credit(killerId, reward, "PvP daily reward");
        recordAbuse("pvp-settlement killer=" + secondId + " victim=" + firstId + " time=" + now + " bank-loss=" + calculatedLoss + " transferred=" + transfer + " base-reward=" + reward + " daily=" + (used + 1) + "/3");
        if (killer != null) killer.sendMessage(color("&a+" + reward + " Marks &7for defeating " + victim.getName()
                + ". Balance: &e" + balance(killerId)));
        victim.sendMessage(color("&c-" + calculatedLoss + " Marks &7for being defeated. Balance: &e"
                + balance(victim.getUniqueId())));
    }

    void suppressNextPvpSettlement(UUID playerId) { pvpSettlementSuppressed.add(playerId); }
    boolean suppressesDeathReward(UUID playerId) { return pvpSettlementSuppressed.contains(playerId); }
    CombatLogService combatLogs() { return combatLogs; }

    @EventHandler(ignoreCancelled = false)
    public void onCurrencyRightClick(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getItem() == null) return;
        long denomination = currency.denomination(event.getItem());
        if (denomination == 0) return;
        Player player = event.getPlayer();
        if (rightClickDepositGuard.contains(player.getUniqueId())) return;

        boolean offhand = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        Long deposited = depositHeldCurrencyUpTo(player, offhand);
        if (deposited == null) {
            player.sendMessage(message("&cCurrency deposit failed to save; no items were consumed."));
            return;
        }
        if (deposited == 0) {
            player.sendMessage(message("&cYour Marks bank is full or cannot fit one item of this denomination. No currency was consumed."));
            return;
        }

        rightClickDepositGuard.add(player.getUniqueId());
        getServer().getScheduler().runTask(this, () -> rightClickDepositGuard.remove(player.getUniqueId()));
        event.setCancelled(true);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.45f, 1.35f);
        player.sendMessage(message("&aDeposited " + deposited + " Marks. Bank balance: " + getMarks(player.getUniqueId()) + "."));
    }

    private LivingEntity qualifiedMobAttacker(Player victim) {
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hit)) return null;
        Entity damager = hit.getDamager();
        LivingEntity attacker = damager instanceof LivingEntity living ? living
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living ? living : null;
        return attacker instanceof Player || attacker == victim ? null : attacker;
    }

    private boolean qualifyingPvpFinalDamage(Player victim, UUID killerId) {
        if (combatLogs != null && combatLogs.isResolving(victim.getUniqueId())) return true;
        EntityDamageEvent cause = victim.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent) {
            Player direct = qualifiedAttacker(victim);
            return direct != null && direct.getUniqueId().equals(killerId);
        }
        if (cause == null) return false;
        return switch (cause.getCause()) {
            case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS -> true;
            default -> false;
        };
    }

    private void applyMobCurrencyPenalty(PlayerDeathEvent event, Player victim) {
        long carried = currency.inventoryValue(victim);
        // Floor to the nearest 10 Marks so rounding cannot exceed 25% of carried value.
        long penalty = (carried / 4 / CurrencyService.DYE_VALUE) * CurrencyService.DYE_VALUE;
        if (penalty <= 0) return;
        event.getDrops().removeIf(item -> currency.denomination(item) != 0);
        if (event.getKeepInventory()) {
            if (!currency.takeExact(victim, penalty)) return;
        } else {
            // Keep all original non-currency death drops. The retained 75% is an owner-locked,
            // authentic currency drop, so a death event never has to clear unrelated inventory.
            event.getDrops().addAll(currency.ownerReturnItems(carried - penalty, victim.getUniqueId()));
        }
        event.getDrops().addAll(currency.itemsFor(penalty));
        victim.sendMessage(message("&cMob death dropped " + penalty + " carried Marks (25%, rounded down to 10 Marks)."));
    }

    @EventHandler(ignoreCancelled = true)
    public void protectRetainedCurrency(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        String owner = currency.returnOwner(stack);
        if (owner == null) return;
        if (!(event.getEntity() instanceof Player player) || !player.getUniqueId().toString().equals(owner)) {
            event.setCancelled(true);
            return;
        }
        currency.unlockOwnerReturn(stack);
        event.getItem().setItemStack(stack);
    }

    @EventHandler
    public void preserveRetainedCurrency(ItemSpawnEvent event) {
        Item item = event.getEntity();
        String owner = currency.returnOwner(item.getItemStack());
        if (owner == null) return;
        try { item.setOwner(UUID.fromString(owner)); } catch (IllegalArgumentException ex) { event.setCancelled(true); return; }
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setCanMobPickup(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void preventRetainedCurrencyDespawn(ItemDespawnEvent event) {
        if (currency.returnOwner(event.getEntity().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void protectRetainedCurrencyFromDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && currency.returnOwner(item.getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void clearDeathPenaltyGuard(PlayerRespawnEvent event) {
        deathEventsProcessed.remove(event.getPlayer().getUniqueId());
    }

    public void handleCommand(CommandSender sender, String commandName, String[] args) {
        if (commandName.equalsIgnoreCase("mark")) { markCommand(sender, args); return; }
        if (!commandName.equalsIgnoreCase("marks")) return;
        if (args.length == 0) {
            if (sender instanceof Player player) sender.sendMessage(color("&eYour balance: &6" + balance(player.getUniqueId()) + " Marks"));
            else sender.sendMessage(color("&cUsage: /marks <top|check|give|take|inspect|reload>"));
            return;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("top") && args.length == 1) { showTop(sender); return; }
        if (sub.equals("reload") && args.length == 1) {
            if (!admin(sender)) return;
            reloadConfig(); sender.sendMessage(color("&aMarks configuration reloaded.")); return;
        }
        if ((sub.equals("check") || sub.equals("inspect")) && args.length == 2) {
            if (!admin(sender)) return;
            OfflinePlayer target = findPlayer(args[1]);
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else sender.sendMessage(color("&e" + playerName(target) + " (&7" + target.getUniqueId() + "&e): &6" + balance(target.getUniqueId()) + " Marks"));
            return;
        }
        if ((sub.equals("give") || sub.equals("take")) && args.length == 3) {
            if (!admin(sender)) return;
            OfflinePlayer target = findPlayer(args[1]);
            Long amount = positiveAmount(args[2]);
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else if (amount == null) sender.sendMessage(color("&cAmount must be a positive whole number."));
            else {
                long current = balance(target.getUniqueId());
                if (sub.equals("give")) credit(target.getUniqueId(), amount, "admin grant by " + sender.getName());
                else set(target.getUniqueId(), amount >= current ? 0 : current - amount);
                sender.sendMessage(color("&a" + (sub.equals("give") ? "Added " : "Removed ") + amount + " Marks. New balance: " + balance(target.getUniqueId())));
            }
            return;
        }
        sender.sendMessage(color("&cUsage: /marks [top|check <player>|give <player> <amount>|take <player> <amount>|inspect <player>|reload]"));
    }

    private void registerPermission(String name, PermissionDefault defaultValue, String description) {
        if (getServer().getPluginManager().getPermission(name) == null)
            getServer().getPluginManager().addPermission(new Permission(name, description, defaultValue));
    }

    private boolean markCommand(CommandSender sender, String[] args) {
        if (args.length == 2 && (args[0].equalsIgnoreCase("ally") || args[0].equalsIgnoreCase("unally"))) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Only players can manage allies."); return true; }
            OfflinePlayer target = findPlayer(args[1]);
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else if (target.getUniqueId().equals(player.getUniqueId())) sender.sendMessage(color("&cYou cannot ally yourself."));
            else {
                Set<UUID> entries = allies.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
                if (args[0].equalsIgnoreCase("ally")) { entries.add(target.getUniqueId()); sender.sendMessage(color("&a" + playerName(target) + " added to your allies.")); }
                else { entries.remove(target.getUniqueId()); sender.sendMessage(color("&e" + playerName(target) + " removed from your allies.")); }
                saveAllies();
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("bounties")) {
            boolean found = false;
            String primeId = null;
            double primeValue = 0;
            for (String key : bountyData.getKeys(false)) {
                double value = Math.max(0, bountyData.getLong(key + ".diamonds")) * getConfig().getDouble("bounties.value.diamond", 1)
                        + Math.max(0, bountyData.getLong(key + ".stars")) * getConfig().getDouble("bounties.value.nether-star", 9)
                        + Math.max(0, bountyData.getLong(key + ".marks"));
                if (value > primeValue) { primeValue = value; primeId = key; }
            }
            if (primeId != null) {
                try { sender.sendMessage(color("&d&lPrime Target: &c" + playerName(getServer().getOfflinePlayer(UUID.fromString(primeId))))); }
                catch (IllegalArgumentException ignored) { }
            }
            sender.sendMessage(color("&6&lMarked Targets"));
            for (String key : bountyData.getKeys(false)) {
                long diamonds = bountyData.getLong(key + ".diamonds");
                long stars = bountyData.getLong(key + ".stars");
                long marks = bountyData.getLong(key + ".marks");
                if (diamonds + stars + marks <= 0) continue;
                OfflinePlayer target;
                try { target = getServer().getOfflinePlayer(UUID.fromString(key)); }
                catch (IllegalArgumentException ex) { continue; }
                sender.sendMessage(color("&c" + playerName(target) + " &7— &b" + diamonds + " diamonds &7+ &d" + stars + " nether stars"));
                if (marks > 0) sender.sendMessage(color("&6  Marks bounty: " + marks));
                found = true;
            }
            if (!found) sender.sendMessage(color("&7There are no active bounties."));
            return true;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("bounty")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Only players can post item bounties."); return true; }
            OfflinePlayer target = findPlayer(args[1]);
            String kind = args[2].toLowerCase();
            Long amount = positiveAmount(args[3]);
            Material material = kind.equals("diamonds") ? Material.DIAMOND : kind.equals("stars") || kind.equals("netherstars") ? Material.NETHER_STAR : null;
            String bountyPath = target == null || material == null ? "" : target.getUniqueId() + (material == Material.DIAMOND ? ".diamonds" : ".stars");
            long bountyLimit = Math.max(0, Math.min(Integer.MAX_VALUE, getConfig().getLong("bounties.max-items-per-type-per-target", 4096)));
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else if (target.getUniqueId().equals(player.getUniqueId())) sender.sendMessage(color("&cYou cannot mark yourself."));
            else if (material == null) sender.sendMessage(color("&cUse diamonds or stars."));
            else if (amount == null || amount > Integer.MAX_VALUE) sender.sendMessage(color("&cAmount must be a positive whole number."));
            else if (amount > bountyLimit - Math.min(bountyLimit, bountyData.getLong(bountyPath))) sender.sendMessage(color("&cThat would exceed the per-target bounty limit of " + bountyLimit + " items."));
            else if (!takeItems(player, material, amount.intValue())) sender.sendMessage(color("&cYou don't have enough " + kind + "."));
            else {
                bountyData.set(bountyPath, safeAdd(bountyData.getLong(bountyPath), amount));
                saveBounties();
                sender.sendMessage(color("&aMarked " + playerName(target) + " with a bounty of " + amount + " " + kind + "."));
            }
            return true;
        }
        sender.sendMessage(color("&eUsage: /mark bounties | /mark bounty <player> <diamonds|stars> <amount>"));
        return true;
    }

    private boolean takeItems(Player player, Material material, int amount) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && item.getType() == material) count += item.getAmount();
        if (count < amount) return false;
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() != material) continue;
            int removed = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            remaining -= removed;
        }
        return true;
    }

    private void payBounty(Player victim, Player killer) {
        String key = victim.getUniqueId().toString();
        List<ItemStack> bountyDrops = new ArrayList<>();
        List<?> storedItems = bountyData.getList(key + ".items", List.of());
        for (Object stored : storedItems) if (stored instanceof ItemStack stack && currency.denomination(stack) != 0)
            bountyDrops.add(stack.clone());
        long oldMarks = bountyData.getLong(key + ".marks");
        long oldDiamonds = bountyData.getLong(key + ".diamonds");
        long oldStars = bountyData.getLong(key + ".stars");
        boolean hadBounty = oldMarks > 0 || oldDiamonds > 0 || oldStars > 0 || !storedItems.isEmpty();
        List<?> oldItems = List.copyOf(storedItems);
        for (String type : new String[]{"diamonds", "stars"}) {
            long recorded = Math.max(0, bountyData.getLong(key + "." + type));
            int amount = (int) Math.min(Integer.MAX_VALUE, recorded);
            if (amount == 0) continue;
            Material material = type.equals("diamonds") ? Material.DIAMOND : Material.NETHER_STAR;
            while (amount > 0) {
                int stackAmount = Math.min(amount, material.getMaxStackSize());
                bountyDrops.add(new ItemStack(material, stackAmount));
                amount -= stackAmount;
            }
        }
        if (bountyDrops.isEmpty()) return;
        bountyData.set(key + ".items", List.of());
        bountyData.set(key + ".marks", 0L);
        bountyData.set(key + ".diamonds", 0L);
        bountyData.set(key + ".stars", 0L);
        if (!persistBounties()) {
            bountyData.set(key + ".items", oldItems);
            bountyData.set(key + ".marks", oldMarks);
            bountyData.set(key + ".diamonds", oldDiamonds);
            bountyData.set(key + ".stars", oldStars);
            return;
        }
        // Commit the one-time claim before spawning drops, so duplicate death handling cannot pay twice.
        for (ItemStack item : bountyDrops) victim.getWorld().dropItemNaturally(victim.getLocation(), item);
        if (hadBounty) {
            for (ItemStack dye : currency.itemsFor(250)) victim.getWorld().dropItemNaturally(victim.getLocation(), dye);
        }
        if (killer != null) killer.sendMessage(color("&6Bounty drops for defeating " + victim.getName() + " are on the ground."));
    }

    private void showTop(CommandSender sender) {
        sender.sendMessage(color("&6&lThe Ledger &7— richest Marks players"));
        balances.entrySet().stream().sorted((a,b) -> Long.compare(b.getValue(), a.getValue())).limit(10).forEachOrdered(entry -> {
            OfflinePlayer player = getServer().getOfflinePlayer(entry.getKey());
            sender.sendMessage(color("&6" + playerName(player) + " &7— &e" + entry.getValue() + " Marks"));
        });
        if (balances.isEmpty()) sender.sendMessage(color("&7No Marks balances have been recorded yet."));
    }
    private boolean admin(CommandSender sender) { if (sender.hasPermission("venmark.admin")) return true; sender.sendMessage(color("&cYou don't have permission.")); return false; }
    private OfflinePlayer findPlayer(String name) { Player online = getServer().getPlayerExact(name); if (online != null) return online; OfflinePlayer offline = getServer().getOfflinePlayer(name); return offline.hasPlayedBefore() ? offline : null; }
    private String playerName(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private Long positiveAmount(String raw) { try { long value = Long.parseLong(raw); return value > 0 ? value : null; } catch (NumberFormatException ex) { return null; } }
    private void saveBounties() { persistBounties(); }
    private boolean persistBounties() {
        if (bountyData == null) return false;
        try { SafeYaml.saveAtomically(bountyData, bountyFile); return true; }
        catch (IOException ex) { getLogger().severe("Could not save bounties: " + ex.getMessage()); return false; }
    }
    private void saveAllies() {
        if (allyData == null) return;
        for (Map.Entry<UUID, Set<UUID>> entry : allies.entrySet()) allyData.set(entry.getKey().toString(), entry.getValue().stream().map(UUID::toString).toList());
        try { SafeYaml.saveAtomically(allyData, allyFile); } catch (IOException ex) { getLogger().severe("Could not save allies: " + ex.getMessage()); }
    }

    boolean isAlly(UUID owner, UUID target) { return allies.getOrDefault(owner, Set.of()).contains(target); }
    Set<UUID> alliesOf(UUID owner) { return Set.copyOf(allies.getOrDefault(owner, Set.of())); }
    boolean isBossMob(Entity entity) {
        return entity instanceof LivingEntity living
                && getConfig().getStringList("rewards.boss-mobs").contains(living.getType().name());
    }
    boolean isBossAttack(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent hit)) return false;
        Entity damager = hit.getDamager();
        if (isBossMob(damager)) return true;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)
            return isBossMob(shooter);
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Entity source)
            return isBossMob(source);
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Entity source)
            return isBossMob(source);
        return false;
    }
    long getMarks(UUID uuid) { return balance(uuid); }
    String selectedClass(UUID uuid) { return classSelection.className(uuid); }

    VanguardProgression.Upgrade abilityTier(UUID uuid, String ability) {
        if ("vanguard".equals(selectedClass(uuid))) {
            VanguardProgression.PlayerData data = vanguardProgression.get(uuid);
            return switch (ability) {
                case "berserker" -> data.berserker;
                case "bloodthirst" -> data.bloodthirst;
                case "ultimate", "blood-blood-blood" -> data.ultimate;
                default -> null;
            };
        }
        if ("phantom".equals(selectedClass(uuid))) {
            String id = switch (ability) {
                case "predators-instinct" -> "predators_instinct";
                case "marked-prey" -> "marked_prey";
                case "into-the-void" -> "into_the_void";
                default -> null;
            };
            return id == null ? null : classProgression.tier(uuid, id);
        }
        if ("warden".equals(selectedClass(uuid))) {
            String id = switch (ability) {
                case "unshaken" -> "unshaken";
                case "sculk-pulse" -> "sculk_pulse";
                case "ancient-guardian" -> "ancient_guardian";
                default -> null;
            };
            return id == null ? null : classProgression.tier(uuid, id);
        }
        return null;
    }
    void enforceClassInventory(Player player) { if (classInventoryRules != null) classInventoryRules.enforce(player); }
    void applyClassAbilities(Player player) { if (classAbilities != null) classAbilities.applyWardenResistance(player); }
    void classChanged(Player player) {
        if (vanguardAbilities != null) vanguardAbilities.classChanged(player);
        if (classAbilities != null) classAbilities.classChanged(player);
        if (wardenUltimates != null) wardenUltimates.classChanged(player);
    }
    long bankRoom(UUID uuid) {
        long current = balance(uuid);
        return current < 0 ? MAX_BANK : Math.max(0L, MAX_BANK - current);
    }
    long currencyDenomination(ItemStack item) { return currency.denomination(item); }
    List<ItemStack> currencyItems(long denomination, int count) { return List.of(currency.create(denomination, count)); }
    List<ItemStack> currencyItemsFor(long amount) { return currency.itemsFor(amount); }
    boolean currencyTakeExact(Player player, long amount) { return currency.takeExact(player, amount); }
    long currencyInventoryValue(Player player) { return currency.inventoryValue(player); }
    long currencyCount(Player player, long denomination) {
        long count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (currency.denomination(item) == denomination) count += item.getAmount();
        if (currency.denomination(player.getInventory().getItemInOffHand()) == denomination) count += player.getInventory().getItemInOffHand().getAmount();
        return count;
    }
    boolean canDeliver(Player player, ItemStack item) {
        ItemStack[] contents = cloneItems(player.getInventory().getStorageContents());
        return addToContents(contents, item).isEmpty();
    }
    boolean deliver(Player player, ItemStack item) {
        ItemStack[] contents = cloneItems(player.getInventory().getStorageContents());
        if (!addToContents(contents, item).isEmpty()) return false;
        player.getInventory().setStorageContents(contents);
        return true;
    }
    boolean hasMaterial(Player player, Material material) {
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && item.getType() == material) return true;
        return player.getInventory().getItemInOffHand().getType() == material;
    }
    boolean takeMaterial(Player player, Material material) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() == 1) contents[i] = null; else item.setAmount(item.getAmount() - 1);
            player.getInventory().setStorageContents(contents);
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() == material) {
            if (offhand.getAmount() == 1) player.getInventory().setItemInOffHand(null);
            else { offhand.setAmount(offhand.getAmount() - 1); player.getInventory().setItemInOffHand(offhand); }
            return true;
        }
        return false;
    }

    boolean depositFromInventory(Player player, long amount) {
        if (amount <= 0 || amount % 10 != 0 || amount > bankRoom(player.getUniqueId())) return false;
        ItemStack[] before = cloneItems(player.getInventory().getStorageContents());
        ItemStack offhand = cloneItem(player.getInventory().getItemInOffHand());
        if (!currency.takeExact(player, amount)) return false;
        if (persistBalance(player.getUniqueId(), balance(player.getUniqueId()) + amount)) return true;
        player.getInventory().setStorageContents(before);
        player.getInventory().setItemInOffHand(offhand);
        return false;
    }

    Long depositHeldCurrency(Player player, boolean offhand) {
        ItemStack held = offhand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        long denomination = currency.denomination(held);
        if (denomination == 0) return null;
        long amount = denomination * held.getAmount();
        if (amount > bankRoom(player.getUniqueId())) return null;
        ItemStack backup = held.clone();
        if (offhand) player.getInventory().setItemInOffHand(null); else player.getInventory().setItemInMainHand(null);
        if (persistBalance(player.getUniqueId(), balance(player.getUniqueId()) + amount)) return amount;
        if (offhand) player.getInventory().setItemInOffHand(backup); else player.getInventory().setItemInMainHand(backup);
        return null;
    }

    private Long depositHeldCurrencyUpTo(Player player, boolean offhand) {
        ItemStack held = offhand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        long denomination = currency.denomination(held);
        if (denomination == 0) return null;
        long room = bankRoom(player.getUniqueId());
        int count = (int) Math.min(held.getAmount(), room / denomination);
        if (count == 0) return 0L;
        long amount = denomination * count;
        ItemStack before = cloneItem(held);
        ItemStack remaining = held.clone();
        if (remaining.getAmount() == count) remaining = null;
        else remaining.setAmount(remaining.getAmount() - count);
        if (offhand) player.getInventory().setItemInOffHand(remaining);
        else player.getInventory().setItemInMainHand(remaining);
        if (persistBalance(player.getUniqueId(), balance(player.getUniqueId()) + amount)) return amount;
        if (offhand) player.getInventory().setItemInOffHand(before);
        else player.getInventory().setItemInMainHand(before);
        return null;
    }

    boolean withdrawPhysical(Player player, long amount, long fee) {
        if (amount <= 0 || amount % 10 != 0 || fee < 0 || amount > Long.MAX_VALUE - fee) return false;
        long total = amount + fee;
        if (balance(player.getUniqueId()) < total) return false;
        ItemStack[] simulated = cloneItems(player.getInventory().getStorageContents());
        for (ItemStack currencyItem : currency.itemsFor(amount)) {
            Map<Integer, ItemStack> overflow = addToContents(simulated, currencyItem);
            if (!overflow.isEmpty()) return false;
        }
        if (!persistBalance(player.getUniqueId(), balance(player.getUniqueId()) - total)) return false;
        player.getInventory().setStorageContents(simulated);
        return true;
    }

    boolean purchaseRelic(Player player, int tier, Material extraPayment, ItemStack relic) {
        if (tier < 1 || tier > 3 || relic == null || relic.getAmount() != 1) return false;
        UUID uuid = player.getUniqueId();
        long bankCost = 0;
        if (balance(uuid) < bankCost) return false;
        int requiredStars = tier == 1 ? 1 : tier >= 2 ? 2 : 0;
        if (currencyCount(player, CurrencyService.STAR_VALUE) < requiredStars) return false;
        if (extraPayment != null && !hasMaterial(player, extraPayment)) return false;

        // Prepare the complete post-transaction inventory before changing balance or items.
        ItemStack[] simulated = cloneItems(player.getInventory().getStorageContents());
        ItemStack simulatedOffhand = cloneItem(player.getInventory().getItemInOffHand());
        int starsLeft = requiredStars;
        for (int i = 0; i < simulated.length && starsLeft > 0; i++) {
            ItemStack stack = simulated[i];
            if (currency.denomination(stack) != CurrencyService.STAR_VALUE) continue;
            int take = Math.min(starsLeft, stack.getAmount());
            if (take == stack.getAmount()) simulated[i] = null; else stack.setAmount(stack.getAmount() - take);
            starsLeft -= take;
        }
        if (starsLeft > 0 && currency.denomination(simulatedOffhand) == CurrencyService.STAR_VALUE) {
            int take = Math.min(starsLeft, simulatedOffhand.getAmount());
            if (take == simulatedOffhand.getAmount()) simulatedOffhand = null; else simulatedOffhand.setAmount(simulatedOffhand.getAmount() - take);
            starsLeft -= take;
        }
        if (starsLeft != 0) return false;
        if (extraPayment != null && !removeMaterial(simulated, extraPayment)) {
            if (simulatedOffhand == null || simulatedOffhand.getType() != extraPayment) return false;
            if (simulatedOffhand.getAmount() == 1) simulatedOffhand = null; else simulatedOffhand.setAmount(simulatedOffhand.getAmount() - 1);
        }
        if (!addToContents(simulated, relic.clone()).isEmpty()) return false;

        ItemStack[] before = cloneItems(player.getInventory().getStorageContents());
        ItemStack beforeOffhand = cloneItem(player.getInventory().getItemInOffHand());
        player.getInventory().setStorageContents(simulated);
        player.getInventory().setItemInOffHand(simulatedOffhand);
        if (bankCost > 0 && !persistBalance(uuid, balance(uuid) - bankCost)) {
            player.getInventory().setStorageContents(before);
            player.getInventory().setItemInOffHand(beforeOffhand);
            return false;
        }
        return true;
    }

    private boolean removeMaterial(ItemStack[] contents, Material material) {
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() == 1) contents[i] = null; else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    Long postPhysicalBounty(Player player, Player target) {
        long amount = currency.inventoryValue(player);
        if (amount <= 0 || player.equals(target)) return null;
        ItemStack[] before = cloneItems(player.getInventory().getStorageContents());
        ItemStack offhand = cloneItem(player.getInventory().getItemInOffHand());
        List<ItemStack> paidItems = new ArrayList<>();
        for (ItemStack item : before) if (currency.denomination(item) != 0) paidItems.add(item.clone());
        if (currency.denomination(offhand) != 0) paidItems.add(offhand.clone());
        if (!currency.takeExact(player, amount)) return null;
        String path = target.getUniqueId() + ".marks";
        long old = bountyData.getLong(path);
        bountyData.set(path, safeAdd(old, amount));
        List<ItemStack> oldItems = new ArrayList<>();
        for (Object stored : bountyData.getList(target.getUniqueId() + ".items", List.of()))
            if (stored instanceof ItemStack stack) oldItems.add(stack.clone());
        List<ItemStack> combinedItems = new ArrayList<>(oldItems);
        combinedItems.addAll(paidItems);
        bountyData.set(target.getUniqueId() + ".items", combinedItems);
        if (!persistBounties()) {
            bountyData.set(path, old);
            bountyData.set(target.getUniqueId() + ".items", oldItems);
            player.getInventory().setStorageContents(before);
            player.getInventory().setItemInOffHand(offhand);
            return null;
        }
        return amount;
    }

    long marksBounty(UUID uuid) { return Math.max(0, bountyData.getLong(uuid + ".marks")); }
    boolean addMarksBounty(UUID target, long amount, UUID sender) {
        if (amount <= 0 || target.equals(sender)) return false;
        String path = target + ".marks"; long old = bountyData.getLong(path);
        bountyData.set(path, safeAdd(old, amount));
        try { SafeYaml.saveAtomically(bountyData, bountyFile); return true; }
        catch (IOException ex) { bountyData.set(path, old); getLogger().severe("Could not save bounty: " + ex.getMessage()); return false; }
    }

    void credit(UUID uuid, long amount, String reason) {
        if (amount <= 0) return;
        long available = bankRoom(uuid);
        long deposited = Math.min(available, amount);
        if (deposited > 0) {
            long updated = balance(uuid) + deposited;
            boolean saved = updated < 0 ? persistBalanceAllowingDebt(uuid, updated) : persistBalance(uuid, updated);
            if (!saved) return;
        }
        long overflow = amount - deposited;
        if (overflow > 0) {
            String path = uuid.toString();
            pendingData.set(path, safeAdd(pendingData.getLong(path), overflow));
            savePending();
            Player player = getServer().getPlayer(uuid);
            if (player != null) player.sendMessage(color("&e" + overflow + " Marks overflowed the 20,000 bank cap and are saved as pending credit."));
        }
        if (reason.startsWith("admin")) audit(reason + " amount=" + amount + " player=" + uuid);
    }

    boolean creditOnce(UUID uuid, long amount, String reason, String transactionId) {
        if (amount <= 0 || transactionId == null || transactionId.isBlank()) return false;
        String marker = "transactions.credits." + transactionId;
        if (balanceData.getBoolean(marker, false)) return true;
        String uuidPath = uuid.toString();
        long current = balance(uuid);
        long deposited = Math.min(bankRoom(uuid), amount);
        long overflow = amount - deposited;
        String pendingMarker = marker;
        if (!pendingData.getBoolean(pendingMarker, false)) {
            long oldPending = pendingData.getLong(uuidPath, 0L);
            pendingData.set(uuidPath, safeAdd(oldPending, overflow));
            pendingData.set(pendingMarker, true);
            if (!saveYamlAtomically(pendingData, pendingFile)) {
                pendingData.set(uuidPath, oldPending);
                pendingData.set(pendingMarker, null);
                getLogger().severe("Could not persist pending part of idempotent Marks credit " + transactionId);
                return false;
            }
        }
        long previous = current;
        long next = safeAdd(current, deposited);
        balances.put(uuid, next);
        balanceData.set(uuidPath, next);
        balanceData.set(marker, true);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balances.put(uuid, previous);
            balanceData.set(uuidPath, previous);
            balanceData.set(marker, null);
            getLogger().severe("Could not persist balance part of idempotent Marks credit " + transactionId);
            return false;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
        if (player != null && overflow > 0)
            player.sendMessage(color("&e" + overflow + " Marks overflowed the bank cap and were saved as pending credit."));
        if (reason.startsWith("admin")) audit(reason + " amount=" + amount + " player=" + uuid);
        return true;
    }

    boolean debitOnce(UUID uuid, long amount, String transactionId) {
        if (amount <= 0 || transactionId == null || transactionId.isBlank()) return false;
        String marker = "transactions.debits." + transactionId;
        if (balanceData.contains(marker)) return balanceData.getLong(marker) == amount;
        long current = balance(uuid);
        if (current < amount) return false;
        long next = current - amount;
        balances.put(uuid, next);
        balanceData.set(uuid.toString(), next);
        balanceData.set(marker, amount);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balances.put(uuid, current);
            balanceData.set(uuid.toString(), current);
            balanceData.set(marker, null);
            return false;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
        return true;
    }

    boolean hasDebitReceipt(String transactionId) {
        return transactionId != null && balanceData != null && balanceData.contains("transactions.debits." + transactionId);
    }

    boolean refundDebitOnce(UUID uuid, String transactionId) {
        if (!hasDebitReceipt(transactionId)) return false;
        long amount = balanceData.getLong("transactions.debits." + transactionId, -1L);
        if (amount <= 0) return false;
        String marker = "transactions.refunds." + transactionId;
        if (balanceData.getBoolean(marker, false)) return true;
        if (!creditOnce(uuid, amount, "transaction refund", "refund:" + transactionId)) return false;
        balanceData.set(marker, true);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balanceData.set(marker, null);
            getLogger().severe("Marks refund was credited but its receipt could not be finalized: " + transactionId);
            return false;
        }
        return true;
    }

    boolean deposit(UUID uuid, long amount) {
        if (amount <= 0 || amount > bankRoom(uuid)) return false;
        return persistBalance(uuid, balance(uuid) + amount);
    }
    boolean debit(UUID uuid, long amount) {
        if (amount < 0 || balance(uuid) < amount) return false;
        return persistBalance(uuid, balance(uuid) - amount);
    }

    boolean deductUpTo(UUID uuid, long amount) {
        if (amount < 0) return false;
        long current = balance(uuid);
        if (current <= 0) return true;
        return persistBalance(uuid, Math.max(0L, current - amount));
    }

    /** Balance-clamped debit with a durable receipt for replayable encounter consequences. */
    boolean deductUpToOnce(UUID uuid, long amount, String transactionId) {
        if (amount < 0 || transactionId == null || transactionId.isBlank()) return false;
        String receipt = "transactions.clamped-debits." + transactionId;
        if (balanceData.contains(receipt)) return true;
        long previous = balance(uuid);
        long deducted = Math.min(previous, amount);
        long next = previous - deducted;
        balances.put(uuid, next);
        balanceData.set(uuid.toString(), next);
        balanceData.set(receipt, deducted);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balances.put(uuid, previous);
            balanceData.set(uuid.toString(), previous);
            balanceData.set(receipt, null);
            return false;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
        return true;
    }

    boolean applyWandererFailurePenalty(UUID uuid, long amount, String transactionId) {
        if (amount < 0 || transactionId == null || transactionId.isBlank()) return false;
        String marker = "transactions.wanderer." + transactionId;
        if (balanceData.getBoolean(marker, false)) return true;
        long previous = balance(uuid);
        long next = safeAdd(previous, -amount);
        balances.put(uuid, next);
        balanceData.set(uuid.toString(), next);
        balanceData.set(marker, true);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balances.put(uuid, previous);
            balanceData.set(uuid.toString(), previous);
            balanceData.set(marker, null);
            getLogger().severe("Could not save Wanderer failure penalty for " + uuid);
            return false;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
        return true;
    }
    void resetMarks(UUID uuid, String actor) {
        set(uuid, 0);
        audit("balance reset by " + actor + " target=" + uuid);
    }
    void audit(String message) { recordAbuse("AUDIT " + System.currentTimeMillis() + " " + message); }
    List<String> abuseRecords() { return abuseLog.size() <= 20 ? List.copyOf(abuseLog) : List.copyOf(abuseLog.subList(abuseLog.size() - 20, abuseLog.size())); }
    boolean withdrawMarks(UUID uuid, long amount) {
        if (amount < 0 || balance(uuid) < amount) return false;
        set(uuid, balance(uuid) - amount);
        return true;
    }
    String message(String message) { return color(message); }
    boolean isRelicDisabled(UUID uuid) { return relicEncounters != null && relicEncounters.isDisabled(uuid); }
    boolean isRelicDisabled(UUID uuid, String ability) { return relicEncounters != null && relicEncounters.isDisabled(uuid, ability); }

    boolean opCooldownBypass(Player player) {
        return player.isOp() && getConfig().getBoolean("settings.opCooldownBypass", false);
    }

    boolean setOpCooldownBypass(boolean enabled) {
        boolean previous = getConfig().getBoolean("settings.opCooldownBypass", false);
        getConfig().set("settings.opCooldownBypass", enabled);
        try {
            getConfig().save(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (IOException | RuntimeException ex) {
            getConfig().set("settings.opCooldownBypass", previous);
            getLogger().severe("Could not save op cooldown bypass setting: " + ex.getMessage());
            return false;
        }
    }

    boolean triggerableAngelEscapes() { return getConfig().getBoolean("settings.triggerableAngelEscapes", false); }

    boolean setTriggerableAngelEscapes(boolean enabled) {
        boolean previous = triggerableAngelEscapes();
        getConfig().set("settings.triggerableAngelEscapes", enabled);
        try {
            getConfig().save(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (IOException | RuntimeException ex) {
            getConfig().set("settings.triggerableAngelEscapes", previous);
            getLogger().severe("Could not save triggerable Angel escapes setting: " + ex.getMessage());
            return false;
        }
    }

    boolean triggerAngelEscape() {
        if (relicEncounters == null) return false;
        relicEncounters.triggerManualWave();
        return true;
    }

    boolean classChangerEnabled() { return getConfig().getBoolean("settings.classChanger", false); }

    boolean setClassChanger(boolean enabled) {
        boolean previous = classChangerEnabled();
        getConfig().set("settings.classChanger", enabled);
        try {
            getConfig().save(new File(getDataFolder(), "config.yml"));
            return true;
        } catch (IOException | RuntimeException ex) {
            getConfig().set("settings.classChanger", previous);
            getLogger().severe("Could not save class changer setting: " + ex.getMessage());
            return false;
        }
    }

    private long balance(UUID uuid) { return balances.getOrDefault(uuid, 0L); }
    private void add(UUID uuid, long amount) { credit(uuid, amount, "legacy credit"); }
    private void set(UUID uuid, long amount) {
        balances.put(uuid, Math.max(0L, Math.min(MAX_BANK, amount)));
        balanceData.set(uuid.toString(), balances.get(uuid));
        saveBalances();
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
    }
    private boolean persistBalance(UUID uuid, long amount) {
        if (amount < 0) return false;
        return persistBalanceAllowingDebt(uuid, amount);
    }

    private boolean persistBalanceAllowingDebt(UUID uuid, long amount) {
        long previous = balance(uuid);
        if (amount > MAX_BANK) return false;
        balances.put(uuid, amount);
        balanceData.set(uuid.toString(), amount);
        if (!saveYamlAtomically(balanceData, balanceFile)) {
            balances.put(uuid, previous);
            balanceData.set(uuid.toString(), previous);
            getLogger().severe("Transaction balance save failed for " + uuid);
            return false;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
        return true;
    }

    private ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = cloneItem(source[i]);
        return result;
    }
    private ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }
    private Map<Integer, ItemStack> addToContents(ItemStack[] contents, ItemStack incoming) {
        ItemStack remain = incoming.clone();
        for (int i = 0; i < contents.length && remain.getAmount() > 0; i++) {
            ItemStack current = contents[i];
            if (current == null || !current.isSimilar(remain)) continue;
            int moved = Math.min(remain.getAmount(), current.getMaxStackSize() - current.getAmount());
            if (moved > 0) { current.setAmount(current.getAmount() + moved); remain.setAmount(remain.getAmount() - moved); }
        }
        for (int i = 0; i < contents.length && remain.getAmount() > 0; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) continue;
            int moved = Math.min(remain.getAmount(), remain.getMaxStackSize());
            contents[i] = remain.clone(); contents[i].setAmount(moved); remain.setAmount(remain.getAmount() - moved);
        }
        if (remain.getAmount() <= 0) return Map.of();
        return Map.of(0, remain);
    }

    private void recordAbuse(String record) {
        abuseLog.add(record);
        if (abuseLog.size() > 5000) abuseLog.remove(0);
        try { java.nio.file.Files.write(abuseFile.toPath(), abuseLog, java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException ex) { getLogger().severe("Could not write abuse log: " + ex.getMessage()); }
    }
    private void savePending() { if (pendingData != null) saveYamlAtomically(pendingData, pendingFile); }
    private boolean saveDaily() {
        if (dailyData == null) return true;
        try { SafeYaml.saveAtomically(dailyData, dailyFile); return true; }
        catch (IOException ex) { getLogger().severe("Could not save daily PvP use: " + ex.getMessage()); return false; }
    }
    private long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }
    private void saveBalances() {
        if (balanceData == null) return;
        saveYamlAtomically(balanceData, balanceFile);
    }

    private boolean saveYamlAtomically(YamlConfiguration yaml, File destination) {
        try {
            SafeYaml.saveAtomically(yaml, destination);
            return true;
        } catch (IOException ex) {
            getLogger().severe("Could not save " + destination.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void saveKillCooldowns() {
        if (killData == null) return;
        try { SafeYaml.saveAtomically(killData, killFile); }
        catch (IOException exception) { getLogger().severe("Could not save kill cooldowns: " + exception.getMessage()); }
    }
    private String color(String message) { return ChatColor.translateAlternateColorCodes('&', message); }
    private void showBalance(Player player) {
        ScoreboardManager manager = getServer().getScoreboardManager();
        if (manager == null) return;
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("venmark", "dummy", color("&6&lVENDETTA"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        player.setScoreboard(scoreboard);
        updateSidebar(player);
    }
    void updateSidebar(Player player) {
        Objective objective = player.getScoreboard().getObjective("venmark");
        if (objective == null) return;
        for (String entry : objective.getScoreboard().getEntries()) objective.getScoreboard().resetScores(entry);
        int cards = wardenUltimates == null ? 0 : wardenUltimates.cards(player.getUniqueId());
        objective.getScore(color(WardenRules.cardDisplayLine(cards))).setScore(2);
        objective.getScore(color("&eMarks: &f" + balance(player.getUniqueId()))).setScore(1);
    }
    private String prettyName(EntityType type) {
        String raw = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
