package dev.prc.vendetta;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Activation input handling is kept separate from effect application/cooldown state. */
final class VanguardAbilityListener implements Listener {
    private final mark plugin;
    private final VanguardProgression progress;
    private final Map<UUID, Long> counterWindows = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Set<UUID> activeUltimates = new HashSet<>();
    private final Set<UUID> counterDamage = new HashSet<>();
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> previousEffects = new HashMap<>();

    VanguardAbilityListener(mark plugin, VanguardProgression progress) {
        this.plugin = plugin;
        this.progress = progress;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireCombatUltimates, 20L, 20L);
    }

    void beginHeadwebWindow(Player player) {
        if (!isVanguard(player) || !isSword(player.getInventory().getItemInMainHand())) return;
        long now = System.currentTimeMillis();
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (data.headwebReadyAt > now) return;
        counterWindows.put(player.getUniqueId(), now + counterWindowMillis(data.headweb));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        if (counterDamage.contains(event.getEntity().getUniqueId())) return;
        LivingEntity source = attacker(event);
        Player attacker = source instanceof Player p ? p : null;
        long now = System.currentTimeMillis();
        if (victim != null && attacker != null && attacker != victim) {
            combatUntil.put(attacker.getUniqueId(), now + plugin.getConfig().getLong("vanguard.ultimate.combat-timeout-seconds", 10) * 1000L);
            combatUntil.put(victim.getUniqueId(), now + plugin.getConfig().getLong("vanguard.ultimate.combat-timeout-seconds", 10) * 1000L);
        }

        // A defensive counter is only eligible for player-caused damage, never fall/fire/poison/etc.
        if (victim != null && source != null && source != victim && isVanguard(victim) && victim.isSneaking()
                && eligibleCombatDamage(event)
                && isSword(victim.getInventory().getItemInMainHand())
                && counterWindows.getOrDefault(victim.getUniqueId(), 0L) >= now) {
            counterWindows.remove(victim.getUniqueId());
            triggerHeadweb(victim, source);
            event.setCancelled(true);
            return;
        }

        if (attacker == null || !isVanguard(attacker)
                || activeUltimates.contains(attacker.getUniqueId()) || !eligibleCombatDamage(event)) return;
        applyBerserker(attacker, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (activeUltimates.contains(event.getPlayer().getUniqueId())
                && progress.get(event.getPlayer().getUniqueId()).ultimate != VanguardProgression.Upgrade.DORMANT) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("&cYou cannot eat or use healing food during this ultimate."));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHealing(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && activeUltimates.contains(player.getUniqueId())
                && progress.get(player.getUniqueId()).ultimate != VanguardProgression.Upgrade.DORMANT) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        VanguardProgression.PlayerData dead = progress.get(victim.getUniqueId());
        if (dead.killStreak != 0) { dead.killStreak = 0; progress.save(victim.getUniqueId()); }
        if (activeUltimates.contains(victim.getUniqueId())) finishUltimate(victim);
        combatUntil.remove(victim.getUniqueId());
        counterWindows.remove(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer != null && isVanguard(killer) && activeUltimates.contains(killer.getUniqueId())
                && progress.get(killer.getUniqueId()).ultimate == VanguardProgression.Upgrade.ASCENDANT) {
            VanguardProgression.PlayerData data = progress.get(killer.getUniqueId());
            data.killStreak++;
            progress.save(killer.getUniqueId());
            int strengthLevel = Math.min(plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-cap", 4),
                    plugin.getConfig().getInt("vanguard.ultimate.ascendant-starting-strength", 2) + data.killStreak);
            killer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                    plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-refresh-seconds", 60) * 20,
                    Math.max(0, strengthLevel - 1), false, true, true));
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (activeUltimates.contains(event.getPlayer().getUniqueId())) finishUltimate(event.getPlayer());
        counterWindows.remove(event.getPlayer().getUniqueId());
        combatUntil.remove(event.getPlayer().getUniqueId());
    }

    void activateUltimate(Player player, boolean totem) {
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (!data.vanguard || activeUltimates.contains(player.getUniqueId())) return;
        long now = System.currentTimeMillis();
        if (data.ultimateReadyAt > now) {
            plugin.getLogger().fine("Vanguard ultimate cooldown blocked " + player.getUniqueId());
            if (!totem) player.sendMessage(plugin.message("&cUltimate ready in " + ((data.ultimateReadyAt - now + 999) / 1000) + "s."));
            return;
        }
        boolean upgraded = data.ultimate != VanguardProgression.Upgrade.DORMANT;
        long duration = plugin.getConfig().getLong("vanguard.ultimate.dormant-duration-seconds", 10) * 1000L;
        long cooldownMinutes = plugin.getConfig().getLong(upgraded ? "vanguard.ultimate.awakened-cooldown-seconds" : "vanguard.ultimate.dormant-cooldown-seconds", upgraded ? 480 : 240);
        data.ultimateReadyAt = now + cooldownMinutes * 1000L;
        progress.save(player.getUniqueId());
        activeUltimates.add(player.getUniqueId());
        boolean perfect = data.ultimate == VanguardProgression.Upgrade.PERFECT || data.ultimate == VanguardProgression.Upgrade.ASCENDANT;
        int strength = Math.max(1, plugin.getConfig().getInt(upgraded ? "vanguard.ultimate.awakened-strength-level" : "vanguard.ultimate.dormant-strength-level", upgraded ? 2 : 1));
        if (data.ultimate == VanguardProgression.Upgrade.ASCENDANT) strength = Math.min(plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-cap", 4), plugin.getConfig().getInt("vanguard.ultimate.ascendant-starting-strength", 2) + data.killStreak);
        int speed = Math.max(1, plugin.getConfig().getInt(upgraded ? "vanguard.ultimate.awakened-speed-level" : "vanguard.ultimate.dormant-speed-level", upgraded ? 2 : 1));
        int ticks = perfect ? 40 : (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration / 50));
        Map<PotionEffectType, PotionEffect> previous = new HashMap<>();
        for (PotionEffectType type : new PotionEffectType[]{PotionEffectType.STRENGTH, PotionEffectType.SPEED, PotionEffectType.FIRE_RESISTANCE}) {
            PotionEffect existing = player.getPotionEffect(type);
            if (existing != null) previous.put(type, existing);
        }
        previousEffects.put(player.getUniqueId(), previous);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, strength - 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, speed - 1, false, true, true));
        if (upgraded && plugin.getConfig().getBoolean("vanguard.ultimate.awakened-fire-resistance", true)) player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, ticks, 0, false, true, true));
        if (perfect) {
            for (UUID allyId : plugin.alliesOf(player.getUniqueId())) {
                Player ally = plugin.getServer().getPlayer(allyId);
                if (ally == null || ally.equals(player)) continue;
                double allyRange = Math.max(0, plugin.getConfig().getDouble("vanguard.ultimate.ally-range-blocks", 16));
                if (!ally.getWorld().equals(player.getWorld()) || ally.getLocation().distanceSquared(player.getLocation()) > allyRange * allyRange) continue;
                int allyTicks = plugin.getConfig().getInt("vanguard.ultimate.ally-duration-seconds", 6) * 20;
                int allyStrength = plugin.getConfig().getInt("vanguard.ultimate.ally-strength-level", 2);
                int allySpeed = plugin.getConfig().getInt("vanguard.ultimate.ally-speed-level", 1);
                ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, allyTicks, allyStrength - 1, false, true, true));
                ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, allyTicks, allySpeed - 1, false, true, true));
            }
        }
        combatUntil.put(player.getUniqueId(), now + plugin.getConfig().getLong("vanguard.ultimate.combat-timeout-seconds", 10) * 1000L);
        if (!perfect) plugin.getServer().getScheduler().runTaskLater(plugin, () -> finishUltimate(player), ticks);
        player.sendMessage(plugin.message("&c&lBLOOD BLOOD BLOOD &7activated."));
        plugin.getLogger().fine("Vanguard ultimate activated for " + player.getUniqueId() + (totem ? " via totem" : " manually"));
    }

    private void applyBerserker(Player attacker, EntityDamageEvent event) {
        VanguardProgression.Upgrade stage = progress.get(attacker.getUniqueId()).berserker;
        double max = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double health = attacker.getHealth();
        if (health >= max * 0.5) return;
        double multiplier;
        if (stage == VanguardProgression.Upgrade.PERFECT && health < max * 0.25)
            multiplier = plugin.getConfig().getDouble("vanguard.berserker.perfect-critical-multiplier", 1.75);
        else if (stage == VanguardProgression.Upgrade.PERFECT)
            multiplier = plugin.getConfig().getDouble("vanguard.berserker.perfect-multiplier", 1.50);
        else if (stage == VanguardProgression.Upgrade.IMPERFECT)
            multiplier = plugin.getConfig().getDouble("vanguard.berserker.imperfect-multiplier", 1.25);
        else multiplier = plugin.getConfig().getDouble("vanguard.berserker.dormant-multiplier", 1.10);
        event.setDamage(event.getDamage() * Math.max(0, multiplier));
    }

    private void triggerHeadweb(Player defender, LivingEntity attacker) {
        VanguardProgression.PlayerData data = progress.get(defender.getUniqueId());
        data.headwebReadyAt = System.currentTimeMillis() + plugin.getConfig().getLong("vanguard.headweb.cooldown-seconds", 12) * 1000L;
        progress.save(defender.getUniqueId());
        int weaknessTicks = data.headweb == VanguardProgression.Upgrade.PERFECT ? plugin.getConfig().getInt("vanguard.headweb.weakness-duration-seconds", 3) * 20 : 0;
        if (weaknessTicks > 0) attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessTicks, plugin.getConfig().getInt("vanguard.headweb.weakness-amplifier", 0), false, true, true));
        int webTicks = plugin.getConfig().getInt("vanguard.headweb.web-duration-ticks", 40);
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, webTicks, plugin.getConfig().getInt("vanguard.headweb.web-slow-amplifier", 2), false, true, true));
        defender.sendMessage(plugin.message("&bHeadweb counter triggered!"));
        plugin.getLogger().fine("Headweb triggered by " + defender.getUniqueId() + " on " + attacker.getUniqueId());
        attacker.sendMessage(plugin.message("&cHeadwebbed!"));
        Location trappedAt = attacker.getLocation().clone();
        int breakDistance = plugin.getConfig().getInt("vanguard.headweb.web-break-distance-blocks", 2);
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int elapsed;
            @Override public void run() {
                if (!attacker.isValid() || attacker.isDead()) { task[0].cancel(); return; }
                elapsed += 5;
                boolean escaped = !attacker.getWorld().equals(trappedAt.getWorld())
                        || attacker.getLocation().distanceSquared(trappedAt) > (double) breakDistance * breakDistance
                        || !attacker.hasPotionEffect(PotionEffectType.SLOWNESS);
                if (!escaped && elapsed < Math.max(5, webTicks)) return;
                task[0].cancel();
                TNTPrimed visual = attacker.getWorld().spawn(attacker.getLocation(), TNTPrimed.class);
                visual.setFuseTicks(1);
                visual.setYield(0);
                visual.setIsIncendiary(false);
                double damage = plugin.getConfig().getDouble("vanguard.headweb.finisher-damage", 4.0);
                counterDamage.add(attacker.getUniqueId());
                try { attacker.damage(Math.max(0, damage), defender); }
                finally { counterDamage.remove(attacker.getUniqueId()); }
            }
        }, 5L, 5L);
    }

    private void expireCombatUltimates() {
        long now = System.currentTimeMillis();
        for (UUID id : Set.copyOf(activeUltimates)) {
            VanguardProgression.PlayerData data = progress.get(id);
            if (data.ultimate == VanguardProgression.Upgrade.PERFECT || data.ultimate == VanguardProgression.Upgrade.ASCENDANT) {
                if (combatUntil.getOrDefault(id, 0L) <= now) {
                    Player player = plugin.getServer().getPlayer(id);
                    if (player != null) finishUltimate(player);
                    else activeUltimates.remove(id);
                } else {
                    Player player = plugin.getServer().getPlayer(id);
                    if (player != null) refreshPerfectUltimate(player);
                }
            }
        }
    }

    private void finishUltimate(Player player) {
        activeUltimates.remove(player.getUniqueId());
        combatUntil.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        Map<PotionEffectType, PotionEffect> previous = previousEffects.remove(player.getUniqueId());
        if (previous != null) previous.forEach((type, effect) -> {
            if (effect.getDuration() > 0) player.addPotionEffect(effect);
        });
        player.sendMessage(plugin.message("&7Your Vanguard ultimate ended."));
    }

    void shutdown() {
        for (UUID id : Set.copyOf(activeUltimates)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) finishUltimate(player);
            else activeUltimates.remove(id);
        }
    }

    private void refreshPerfectUltimate(Player player) {
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        int strength = data.ultimate == VanguardProgression.Upgrade.ASCENDANT
                ? Math.min(plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-cap", 4), plugin.getConfig().getInt("vanguard.ultimate.ascendant-starting-strength", 2) + data.killStreak)
                : plugin.getConfig().getInt("vanguard.ultimate.awakened-strength-level", 2);
        int speed = plugin.getConfig().getInt("vanguard.ultimate.awakened-speed-level", 2);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 40, Math.max(0, strength - 1), false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, Math.max(0, speed - 1), false, true, true));
        if (plugin.getConfig().getBoolean("vanguard.ultimate.awakened-fire-resistance", true))
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40, 0, false, true, true));
    }

    private boolean eligibleCombatDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK || cause == DamageCause.PROJECTILE;
    }

    private LivingEntity attacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living) return living;
        return null;
    }
    private boolean isVanguard(Player player) { return progress.get(player.getUniqueId()).vanguard; }
    private boolean isSword(ItemStack item) { return item != null && item.getType().name().endsWith("_SWORD"); }
    private long counterWindowMillis(VanguardProgression.Upgrade upgrade) {
        return plugin.getConfig().getLong(upgrade == VanguardProgression.Upgrade.DORMANT ? "vanguard.headweb.dormant-window-ms" : "vanguard.headweb.awakened-window-ms", upgrade == VanguardProgression.Upgrade.DORMANT ? 1000 : 2000);
    }
}
