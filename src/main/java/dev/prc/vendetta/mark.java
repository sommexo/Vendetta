package dev.prc.vendetta;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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

public final class mark extends JavaPlugin implements Listener {
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, Long> recentKills = new HashMap<>();
    private File balanceFile;
    private YamlConfiguration balanceData;
    private File killFile;
    private YamlConfiguration killData;

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
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + balances.size() + " Marks balances.");
    }

    @Override
    public void onDisable() {
        saveBalances();
        saveKillCooldowns();
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
        killer.sendMessage(color("&a+" + reward + " Marks &7for defeating " + victim.getName()
                + ". Balance: &e" + balance(killer.getUniqueId())));
        victim.sendMessage(color("&c-" + penalty + " Marks &7for being defeated. Balance: &e"
                + balance(victim.getUniqueId())));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("marks")) return false;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have a Marks balance.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("venmark.admin")) {
                sender.sendMessage(color("&cYou don't have permission."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(color("&aMarks configuration reloaded."));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            if (!sender.hasPermission("venmark.admin")) {
                sender.sendMessage(color("&cYou don't have permission."));
                return true;
            }
            Player target = getServer().getPlayerExact(args[1]);
            if (target == null) sender.sendMessage(color("&cThat player must be online."));
            else sender.sendMessage(color("&e" + target.getName() + " has " + balance(target.getUniqueId()) + " Marks."));
            return true;
        }
        sender.sendMessage(color("&eYour balance: &6" + balance(player.getUniqueId()) + " Marks"));
        return true;
    }

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
