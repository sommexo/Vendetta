package dev.prc.vendetta;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks the shared 15-second PvP window and durably records combat loggers. */
final class CombatLogService implements Listener {
    private static final long WINDOW_MS = 15_000L;
    private final mark plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> activeUntil = new HashMap<>();
    private final Map<UUID, UUID> opponents = new HashMap<>();
    private final Map<UUID, UUID> recentAttackers = new HashMap<>();
    private final Map<UUID, Long> recentAttackerAt = new HashMap<>();
    private final Map<UUID, UUID> resolving = new HashMap<>();

    CombatLogService(mark plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "combat-log.yml");
        data = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        long now = System.currentTimeMillis();
        Player attacker = directAttacker(event);
        if (attacker != null && attacker != victim) {
            tag(victim.getUniqueId(), attacker.getUniqueId(), now);
            tag(attacker.getUniqueId(), victim.getUniqueId(), now);
            recentAttackers.put(victim.getUniqueId(), attacker.getUniqueId());
            recentAttackerAt.put(victim.getUniqueId(), now);
            return;
        }
        if (statusOrIndirectDamage(event) && now - recentAttackerAt.getOrDefault(victim.getUniqueId(), 0L) <= WINDOW_MS) {
            UUID source = recentAttackers.get(victim.getUniqueId());
            if (source != null) {
                activeUntil.put(victim.getUniqueId(), now + WINDOW_MS);
                activeUntil.put(source, now + WINDOW_MS);
                opponents.put(victim.getUniqueId(), source);
                opponents.put(source, victim.getUniqueId());
            }
        }
    }

    private void tag(UUID player, UUID opponent, long now) {
        activeUntil.put(player, now + WINDOW_MS);
        opponents.put(player, opponent);
    }

    private boolean statusOrIndirectDamage(EntityDamageEvent event) {
        return switch (event.getCause()) {
            case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS, FIRE, FIRE_TICK, HOT_FLOOR, LAVA -> true;
            default -> false;
        };
    }

    private Player directAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    UUID killerFor(Player victim) {
        Player direct = directAttacker(victim.getLastDamageCause());
        if (direct != null && !direct.equals(victim)) return direct.getUniqueId();
        UUID pending = resolving.get(victim.getUniqueId());
        if (pending != null) return pending;
        UUID attacker = recentAttackers.get(victim.getUniqueId());
        if (attacker != null && System.currentTimeMillis() - recentAttackerAt.getOrDefault(victim.getUniqueId(), 0L) <= WINDOW_MS) return attacker;
        String stored = data.getString(victim.getUniqueId() + ".killer");
        if (stored == null) return null;
        try { return UUID.fromString(stored); } catch (IllegalArgumentException ex) { return null; }
    }

    boolean isResolving(UUID uuid) { return resolving.containsKey(uuid); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        if (activeUntil.getOrDefault(id, 0L) > now) {
            UUID opponent = opponents.get(id);
            if (opponent == null) opponent = recentAttackers.get(id);
            if (opponent != null && !opponent.equals(id)) {
                data.set(id + ".killer", opponent.toString());
                data.set(id + ".quit-at", now);
                data.set(id + ".state", "pending");
                save();
            }
        }
        clearPlayer(id);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String state = data.getString(player.getUniqueId() + ".state");
        String rawKiller = data.getString(player.getUniqueId() + ".killer");
        if (!"pending".equals(state) || rawKiller == null) return;
        UUID killer;
        try { killer = UUID.fromString(rawKiller); } catch (IllegalArgumentException ex) { complete(player.getUniqueId()); return; }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;
            resolving.put(player.getUniqueId(), killer);
            plugin.resolveCombatLog(player, killer);
            player.setHealth(0.0);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID victim = event.getEntity().getUniqueId();
        if (resolving.containsKey(victim) || data.contains(victim + ".state")) complete(victim);
        clearPlayer(victim);
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { clearPlayer(event.getPlayer().getUniqueId()); }

    void complete(UUID uuid) {
        data.set(uuid + ".killer", null);
        data.set(uuid + ".quit-at", null);
        data.set(uuid + ".state", null);
        resolving.remove(uuid);
        save();
    }

    private void clearPlayer(UUID uuid) {
        UUID opponent = opponents.remove(uuid);
        activeUntil.remove(uuid); recentAttackers.remove(uuid); recentAttackerAt.remove(uuid);
        if (opponent != null && uuid.equals(opponents.get(opponent))) {
            opponents.remove(opponent); activeUntil.remove(opponent);
        }
    }

    void shutdown() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<UUID, Long> entry : activeUntil.entrySet()) {
            if (entry.getValue() <= now || plugin.getServer().getPlayer(entry.getKey()) == null) continue;
            UUID opponent = opponents.get(entry.getKey());
            if (opponent == null || opponent.equals(entry.getKey())) continue;
            data.set(entry.getKey() + ".killer", opponent.toString());
            data.set(entry.getKey() + ".quit-at", now);
            data.set(entry.getKey() + ".state", "pending");
            changed = true;
        }
        if (changed) save();
        activeUntil.clear(); opponents.clear(); recentAttackers.clear(); recentAttackerAt.clear(); resolving.clear();
    }

    private void save() {
        try { data.save(file); }
        catch (IOException ex) { plugin.getLogger().severe("Could not save combat log records: " + ex.getMessage()); }
    }
}
