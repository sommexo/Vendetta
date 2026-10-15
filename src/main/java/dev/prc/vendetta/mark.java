package dev.prc.vendetta;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
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

public final class mark extends JavaPlugin implements Listener {
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, Long> recentKills = new HashMap<>();
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
    private VanguardAbilityListener vanguardAbilities;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        balanceFile = new File(getDataFolder(), "balances.yml");
        balanceData = YamlConfiguration.loadConfiguration(balanceFile);
        for (String key : balanceData.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), Math.max(0L, balanceData.getLong(key)));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid UUID in balances.yml: " + key);
            }
        }
        killFile = new File(getDataFolder(), "kill-cooldowns.yml");
        killData = YamlConfiguration.loadConfiguration(killFile);
        for (String key : killData.getKeys(false)) recentKills.put(key, killData.getLong(key));
        bountyFile = new File(getDataFolder(), "bounties.yml");
        bountyData = YamlConfiguration.loadConfiguration(bountyFile);
        allyFile = new File(getDataFolder(), "allies.yml");
        allyData = YamlConfiguration.loadConfiguration(allyFile);
        for (String owner : allyData.getKeys(false)) {
            try {
                Set<UUID> entries = new HashSet<>();
                for (String id : allyData.getStringList(owner)) try { entries.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
                allies.put(UUID.fromString(owner), entries);
            } catch (IllegalArgumentException ignored) { }
        }
        vanguardProgression = new VanguardProgression(getDataFolder());
        getServer().getPluginManager().registerEvents(this, this);
        vanguardAbilities = new VanguardAbilityListener(this, vanguardProgression);
        getServer().getPluginManager().registerEvents(vanguardAbilities, this);
        getServer().getPluginManager().registerEvents(new VanguardActivationHandler(this, vanguardProgression, vanguardAbilities), this);
        RelicService relicService = new RelicService(this, vanguardProgression);
        getServer().getPluginManager().registerEvents(relicService, this);
        if (getCommand("vanguard") != null) getCommand("vanguard").setExecutor(new VanguardCommands(this, vanguardProgression));
        getLogger().info("Loaded " + balances.size() + " Marks balances.");
    }

    @Override
    public void onDisable() {
        if (vanguardAbilities != null) vanguardAbilities.shutdown();
        saveBalances();
        saveKillCooldowns();
        saveBounties();
        saveAllies();
        vanguardProgression.saveAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        balances.putIfAbsent(event.getPlayer().getUniqueId(), 0L);
        showBalance(event.getPlayer());
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || entity instanceof Player) return;

        long reward = mobReward(entity.getType());
        if (reward <= 0) return;
        add(killer.getUniqueId(), reward);
        killer.sendMessage(color("&a+" + reward + " Marks &7for defeating a " + prettyName(entity.getType())
                + ". Balance: &e" + balance(killer.getUniqueId())));
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
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, getConfig().getLong("pvp.repeat-kill-cooldown-seconds", 600)) * 1000L;
        String firstId = victim.getUniqueId().toString();
        String secondId = killer.getUniqueId().toString();
        String pair = firstId.compareTo(secondId) < 0 ? firstId + ":" + secondId : secondId + ":" + firstId;
        Long previous = recentKills.get(pair);
        if (previous != null && now - previous < cooldown) {
            killer.sendMessage(color("&cNo Marks awarded: repeated kills are blocked for "
                    + Math.max(1, (cooldown - (now - previous) + 999) / 1000) + " more seconds."));
            return;
        }
        recentKills.put(pair, now);
        killData.set(pair, now);
        saveKillCooldowns();

        long victimBalance = balance(victim.getUniqueId());
        long bonus = (long) Math.floor(victimBalance * getConfig().getDouble("pvp.bounty-percent", 0.25));
        long reward = safeAdd(getConfig().getLong("pvp.base-reward", 500), bonus);
        long penalty = Math.min(victimBalance, Math.max(0L, getConfig().getLong("pvp.death-penalty", 2500)));
        add(killer.getUniqueId(), reward);
        set(victim.getUniqueId(), victimBalance - penalty);
        payBounty(victim, killer);
        killer.sendMessage(color("&a+" + reward + " Marks &7for defeating " + victim.getName()
                + ". Balance: &e" + balance(killer.getUniqueId())));
        victim.sendMessage(color("&c-" + penalty + " Marks &7for being defeated. Balance: &e"
                + balance(victim.getUniqueId())));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mark")) return markCommand(sender, args);
        if (!command.getName().equalsIgnoreCase("marks")) return false;
        if (args.length == 0) {
            if (sender instanceof Player player) sender.sendMessage(color("&eYour balance: &6" + balance(player.getUniqueId()) + " Marks"));
            else sender.sendMessage(color("&cUsage: /marks <top|check|give|take|inspect|reload>"));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("top") && args.length == 1) { showTop(sender); return true; }
        if (sub.equals("reload") && args.length == 1) {
            if (!admin(sender)) return true;
            reloadConfig(); sender.sendMessage(color("&aMarks configuration reloaded.")); return true;
        }
        if ((sub.equals("check") || sub.equals("inspect")) && args.length == 2) {
            if (!admin(sender)) return true;
            OfflinePlayer target = findPlayer(args[1]);
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else sender.sendMessage(color("&e" + playerName(target) + " (&7" + target.getUniqueId() + "&e): &6" + balance(target.getUniqueId()) + " Marks"));
            return true;
        }
        if ((sub.equals("give") || sub.equals("take")) && args.length == 3) {
            if (!admin(sender)) return true;
            OfflinePlayer target = findPlayer(args[1]);
            Long amount = positiveAmount(args[2]);
            if (target == null) sender.sendMessage(color("&cPlayer not found."));
            else if (amount == null) sender.sendMessage(color("&cAmount must be a positive whole number."));
            else {
                long current = balance(target.getUniqueId());
                set(target.getUniqueId(), sub.equals("give") ? safeAdd(current, amount) : amount >= current ? 0 : current - amount);
                sender.sendMessage(color("&a" + (sub.equals("give") ? "Added " : "Removed ") + amount + " Marks. New balance: " + balance(target.getUniqueId())));
            }
            return true;
        }
        sender.sendMessage(color("&cUsage: /marks [top|check <player>|give <player> <amount>|take <player> <amount>|inspect <player>|reload]"));
        return true;
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
                        + Math.max(0, bountyData.getLong(key + ".stars")) * getConfig().getDouble("bounties.value.nether-star", 9);
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
                if (diamonds + stars <= 0) continue;
                OfflinePlayer target;
                try { target = getServer().getOfflinePlayer(UUID.fromString(key)); }
                catch (IllegalArgumentException ex) { continue; }
                sender.sendMessage(color("&c" + playerName(target) + " &7— &b" + diamonds + " diamonds &7+ &d" + stars + " nether stars"));
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
        for (String type : new String[]{"diamonds", "stars"}) {
            int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(0, bountyData.getLong(key + "." + type)));
            if (amount == 0) continue;
            Material material = type.equals("diamonds") ? Material.DIAMOND : Material.NETHER_STAR;
            Map<Integer, ItemStack> overflow = killer.getInventory().addItem(new ItemStack(material, amount));
            for (ItemStack item : overflow.values()) killer.getWorld().dropItemNaturally(killer.getLocation(), item);
            bountyData.set(key + "." + type, 0);
            killer.sendMessage(color("&6Claimed " + amount + " " + type + " bounty for defeating " + victim.getName() + "!"));
        }
        saveBounties();
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
    private void saveBounties() { if (bountyData == null) return; try { bountyData.save(bountyFile); } catch (IOException ex) { getLogger().severe("Could not save bounties: " + ex.getMessage()); } }
    private void saveAllies() {
        if (allyData == null) return;
        for (Map.Entry<UUID, Set<UUID>> entry : allies.entrySet()) allyData.set(entry.getKey().toString(), entry.getValue().stream().map(UUID::toString).toList());
        try { allyData.save(allyFile); } catch (IOException ex) { getLogger().severe("Could not save allies: " + ex.getMessage()); }
    }

    boolean isAlly(UUID owner, UUID target) { return allies.getOrDefault(owner, Set.of()).contains(target); }
    Set<UUID> alliesOf(UUID owner) { return Set.copyOf(allies.getOrDefault(owner, Set.of())); }
    long getMarks(UUID uuid) { return balance(uuid); }
    boolean withdrawMarks(UUID uuid, long amount) {
        if (amount < 0 || balance(uuid) < amount) return false;
        set(uuid, balance(uuid) - amount);
        return true;
    }
    String message(String message) { return color(message); }

    private long balance(UUID uuid) { return balances.getOrDefault(uuid, 0L); }
    private void add(UUID uuid, long amount) { set(uuid, safeAdd(balance(uuid), Math.max(0L, amount))); }
    private void set(UUID uuid, long amount) {
        balances.put(uuid, Math.max(0L, amount));
        balanceData.set(uuid.toString(), balances.get(uuid));
        saveBalances();
        Player player = getServer().getPlayer(uuid);
        if (player != null) updateSidebar(player);
    }
    private long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }
    private void saveBalances() {
        if (balanceData == null) return;
        try { balanceData.save(balanceFile); }
        catch (IOException exception) { getLogger().severe("Could not save Marks balances: " + exception.getMessage()); }
    }
    private void saveKillCooldowns() {
        if (killData == null) return;
        try { killData.save(killFile); }
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
    private void updateSidebar(Player player) {
        Objective objective = player.getScoreboard().getObjective("venmark");
        if (objective == null) return;
        for (String entry : objective.getScoreboard().getEntries()) objective.getScoreboard().resetScores(entry);
        objective.getScore(color("&eMarks: &f" + balance(player.getUniqueId()))).setScore(1);
    }
    private String prettyName(EntityType type) {
        String raw = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
