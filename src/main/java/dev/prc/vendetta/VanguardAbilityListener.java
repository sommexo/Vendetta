package dev.prc.vendetta;

import org.bukkit.Material;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

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
    private final Map<UUID, Set<UUID>> combatOpponents = new HashMap<>();
    private final Map<UUID, UUID> recentPlayerAttacker = new HashMap<>();
    private final Map<UUID, Long> recentPlayerAttackAt = new HashMap<>();
    private final Set<UUID> activeUltimates = new HashSet<>();
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> previousEffects = new HashMap<>();

    VanguardAbilityListener(mark plugin, VanguardProgression progress) {
        this.plugin = plugin;
        this.progress = progress;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireCombatUltimates, 20L, 20L);
    }

    void beginBloodthirstWindow(Player player) {
        if (!isVanguard(player) || !isSword(player.getInventory().getItemInMainHand())) return;
        long now = System.currentTimeMillis();
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (data.bloodthirstReadyAt > now && !plugin.opCooldownBypass(player)) return;
        if (counterWindows.getOrDefault(player.getUniqueId(), 0L) > now) return;
        long deadline = now + counterWindowMillis(data.bloodthirst);
        long oldCooldown = data.bloodthirstReadyAt;
        data.bloodthirstReadyAt = deadline
                + plugin.getConfig().getLong("vanguard.bloodthirst.cooldown-seconds", 30) * 1000L;
        try { progress.save(player.getUniqueId()); }
        catch (RuntimeException ex) {
            data.bloodthirstReadyAt = oldCooldown;
            try { progress.save(player.getUniqueId()); } catch (RuntimeException rollback) { ex.addSuppressed(rollback); }
            plugin.getLogger().severe("Could not save Bloodthirst cooldown for " + player.getUniqueId() + ": " + ex.getMessage());
            return;
        }
        counterWindows.put(player.getUniqueId(), deadline);
        player.sendMessage(plugin.message("&bBloodthirst armed"));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            counterWindows.remove(player.getUniqueId(), deadline);
        }, Math.max(1L, (deadline - now + 49) / 50));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        LivingEntity source = attacker(event);
        Player attacker = source instanceof Player p ? p : null;
        long now = System.currentTimeMillis();
        if (victim != null && event instanceof EntityDamageByEntityEvent && attacker != null && attacker != victim) {
            recentPlayerAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
            recentPlayerAttackAt.put(victim.getUniqueId(), now);
        }
        if (eligibleCombatDamage(event)) {
            long combatDeadline = now + plugin.getConfig().getLong("combat.timeout-seconds", 15) * 1000L;
            if (source instanceof Player sourcePlayer && victim != null && sourcePlayer != victim) {
                combatUntil.put(victim.getUniqueId(), combatDeadline);
                combatUntil.put(sourcePlayer.getUniqueId(), combatDeadline);
                combatOpponents.computeIfAbsent(victim.getUniqueId(), ignored -> new HashSet<>()).add(sourcePlayer.getUniqueId());
                combatOpponents.computeIfAbsent(sourcePlayer.getUniqueId(), ignored -> new HashSet<>()).add(victim.getUniqueId());
            }
            if (source instanceof Player sourcePlayer && plugin.isBossMob(event.getEntity()))
                combatUntil.put(sourcePlayer.getUniqueId(), combatDeadline);
            if (victim != null && plugin.isBossAttack(event))
                combatUntil.put(victim.getUniqueId(), combatDeadline);
        }

        // Bloodthirst can only counter a direct attacker while the Vanguard is crouching with a sword.
        if (victim != null && source != null && source != victim && isVanguard(victim) && victim.isSneaking()
                && event instanceof EntityDamageByEntityEvent && eligibleCombatDamage(event)
                && isSword(victim.getInventory().getItemInMainHand())
                && counterWindows.getOrDefault(victim.getUniqueId(), 0L) >= now) {
            counterWindows.remove(victim.getUniqueId());
            triggerBloodthirst(victim);
        }

        if (!(event instanceof EntityDamageByEntityEvent) || attacker == null || !isVanguard(attacker) || !isVanguardWeapon(attacker.getInventory().getItemInMainHand())
                || activeUltimates.contains(attacker.getUniqueId()) || !eligibleCombatDamage(event)) return;
        applyBerserker(attacker, event);
    }






    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHealing(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && activeUltimates.contains(player.getUniqueId())
                && progress.get(player.getUniqueId()).ultimate != VanguardProgression.Upgrade.DORMANT) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (VanguardRules.blocksUltimateFood(data.vanguard, activeUltimates.contains(player.getUniqueId()), data.ultimate))
            event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        VanguardProgression.PlayerData dead = progress.get(victim.getUniqueId());
        if (dead.grimReaperLevel != 0 || dead.ascendantKillStreak != 0) {
            dead.grimReaperLevel = 0;
            dead.ascendantKillStreak = 0;
            dead.killStreak = 0;
            progress.save(victim.getUniqueId());
            if (!activeUltimates.contains(victim.getUniqueId())) victim.removePotionEffect(PotionEffectType.STRENGTH);
        }
        if (activeUltimates.contains(victim.getUniqueId())) finishUltimate(victim);
        combatUntil.remove(victim.getUniqueId());
        clearOpponentLinks(victim.getUniqueId());
        UUID killerId = victim.getKiller() == null ? null : victim.getKiller().getUniqueId();
        if (killerId == null && plugin.combatLogs() != null) killerId = plugin.combatLogs().killerFor(victim);
        Player killer = killerId == null ? null : plugin.getServer().getPlayer(killerId);
        counterWindows.remove(victim.getUniqueId());
        if (plugin.suppressesDeathReward(victim.getUniqueId())) return;
        if (killer != null && killer != victim && isQualifyingPlayerKill(victim, killer)) {
            VanguardProgression.PlayerData data = progress.get(killer.getUniqueId());
            data.grimReaperLevel = Math.min(4, data.grimReaperLevel + 1);
            if (data.ultimate == VanguardProgression.Upgrade.ASCENDANT)
                data.ascendantKillStreak++;
            data.killStreak = data.ascendantKillStreak;
            progress.save(killer.getUniqueId());
            applyGrimReaperStrength(killer, data.grimReaperLevel);
            if (activeUltimates.contains(killer.getUniqueId()) && data.ultimate == VanguardProgression.Upgrade.ASCENDANT)
                refreshUltimate(killer);
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { refreshAscendantStreak(event.getPlayer()); }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        recentPlayerAttacker.remove(player.getUniqueId());
        recentPlayerAttackAt.remove(player.getUniqueId());
        clearOpponentLinks(player.getUniqueId());
        if (activeUltimates.contains(player.getUniqueId())) finishUltimate(player);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (activeUltimates.contains(event.getPlayer().getUniqueId())) finishUltimate(event.getPlayer());
        counterWindows.remove(event.getPlayer().getUniqueId());
        clearOpponentLinks(event.getPlayer().getUniqueId());
    }

    void activateUltimate(Player player, boolean totem) {
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (!data.vanguard) return;
        if (activeUltimates.contains(player.getUniqueId())) {
            if (totem) finishUltimate(player);
            return;
        }
        long now = System.currentTimeMillis();
        boolean bypassCooldown = plugin.opCooldownBypass(player);
        if (data.ultimateReadyAt > now && !bypassCooldown) {
            plugin.getLogger().fine("Vanguard ultimate cooldown blocked " + player.getUniqueId());
            if (!totem) player.sendMessage(plugin.message("&cUltimate ready in " + ((data.ultimateReadyAt - now + 999) / 1000) + "s."));
            return;
        }
        boolean upgraded = data.ultimate != VanguardProgression.Upgrade.DORMANT;
        long cooldownSeconds = data.ultimate == VanguardProgression.Upgrade.ASCENDANT
                ? plugin.getConfig().getLong("vanguard.ultimate.ascendant-cooldown-seconds", 2400)
                : plugin.getConfig().getLong(upgraded ? "vanguard.ultimate.awakened-cooldown-seconds" : "vanguard.ultimate.dormant-cooldown-seconds", upgraded ? 480 : 240);
        data.ultimateReadyAt = now + cooldownSeconds * 1000L;
        progress.save(player.getUniqueId());
        activeUltimates.add(player.getUniqueId());
        boolean perfect = data.ultimate == VanguardProgression.Upgrade.PERFECT || data.ultimate == VanguardProgression.Upgrade.ASCENDANT;
        int strength = Math.max(1, plugin.getConfig().getInt(upgraded ? "vanguard.ultimate.awakened-strength-level" : "vanguard.ultimate.dormant-strength-level", upgraded ? 2 : 1));
        if (data.ultimate == VanguardProgression.Upgrade.ASCENDANT) strength = Math.min(plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-cap", 4), plugin.getConfig().getInt("vanguard.ultimate.ascendant-starting-strength", 2) + data.ascendantKillStreak);
        strength = Math.max(strength, Math.min(4, data.grimReaperLevel));
        int speed = Math.max(1, plugin.getConfig().getInt(upgraded ? "vanguard.ultimate.awakened-speed-level" : "vanguard.ultimate.dormant-speed-level", upgraded ? 2 : 1));
        int ticks = 40;
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
        combatUntil.put(player.getUniqueId(), now + plugin.getConfig().getLong("combat.timeout-seconds", 15) * 1000L);
        player.sendMessage(plugin.message("&c&lBLOOD BLOOD BLOOD &7activated."));
        plugin.getLogger().fine("Vanguard ultimate activated for " + player.getUniqueId() + " via totem");
    }

    private void applyBerserker(Player attacker, EntityDamageEvent event) {
        VanguardProgression.Upgrade stage = progress.get(attacker.getUniqueId()).berserker;
        double multiplier = VanguardRules.berserkerMultiplier(stage, attacker.getHealth());
        if (multiplier != 1.0) event.setDamage(event.getDamage() * multiplier);
    }

    private void triggerBloodthirst(Player defender) {
        VanguardProgression.PlayerData data = progress.get(defender.getUniqueId());
        data.bloodthirstReadyAt = System.currentTimeMillis()
                + plugin.getConfig().getLong("vanguard.bloodthirst.cooldown-seconds", 30) * 1000L;
        progress.save(defender.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (defender.isOnline() && !defender.isDead()) {
            double maximum = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                defender.setHealth(maximum);
            }
        });
        defender.sendMessage(plugin.message("&bBloodthirst counter! You recovered health."));
        plugin.getLogger().fine("Bloodthirst triggered by " + defender.getUniqueId());
    }

    private void expireCombatUltimates() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) refreshAscendantStreak(player);
        for (UUID id : Set.copyOf(combatUntil.keySet()))
            if (combatUntil.getOrDefault(id, 0L) <= now) clearOpponentLinks(id);
        for (UUID id : Set.copyOf(activeUltimates)) {
            if (combatUntil.getOrDefault(id, 0L) <= now) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) finishUltimate(player);
                else activeUltimates.remove(id);
            } else {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) refreshUltimate(player);
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
        refreshAscendantStreak(player);
    }

    private void clearOpponentLinks(UUID playerId) {
        combatUntil.remove(playerId);
        Set<UUID> opponents = combatOpponents.remove(playerId);
        if (opponents == null) return;
        for (UUID opponentId : opponents) {
            Set<UUID> remaining = combatOpponents.get(opponentId);
            if (remaining == null) continue;
            remaining.remove(playerId);
            if (remaining.isEmpty()) {
                combatOpponents.remove(opponentId);
                combatUntil.remove(opponentId);
                Player opponent = plugin.getServer().getPlayer(opponentId);
                if (opponent != null && activeUltimates.contains(opponentId)) finishUltimate(opponent);
            }
        }
    }

    void shutdown() {
        for (UUID id : Set.copyOf(activeUltimates)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) finishUltimate(player);
            else activeUltimates.remove(id);
        }
    }

    void classChanged(Player player) {
        UUID id = player.getUniqueId();
        counterWindows.remove(id);
        if (activeUltimates.contains(id)) finishUltimate(player);
    }

    private void refreshUltimate(Player player) {
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        boolean dormant = data.ultimate == VanguardProgression.Upgrade.DORMANT;
        int strength = dormant ? plugin.getConfig().getInt("vanguard.ultimate.dormant-strength-level", 1)
                : data.ultimate == VanguardProgression.Upgrade.ASCENDANT
                ? Math.min(plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-cap", 4), plugin.getConfig().getInt("vanguard.ultimate.ascendant-starting-strength", 2) + data.ascendantKillStreak)
                : plugin.getConfig().getInt("vanguard.ultimate.awakened-strength-level", 2);
        strength = Math.max(strength, Math.min(4, data.grimReaperLevel));
        int speed = plugin.getConfig().getInt(dormant ? "vanguard.ultimate.dormant-speed-level" : "vanguard.ultimate.awakened-speed-level", dormant ? 1 : 2);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 40, Math.max(0, strength - 1), false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, Math.max(0, speed - 1), false, true, true));
        if (!dormant && plugin.getConfig().getBoolean("vanguard.ultimate.awakened-fire-resistance", true))
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40, 0, false, true, true));
    }

    private void refreshAscendantStreak(Player player) {
        VanguardProgression.PlayerData data = progress.get(player.getUniqueId());
        if (!data.vanguard || data.grimReaperLevel <= 0) return;
        applyGrimReaperStrength(player, data.grimReaperLevel);
    }

    private void applyGrimReaperStrength(Player player, int level) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60,
                Math.max(0, Math.min(4, level) - 1), false, true, true), true);
    }

    private boolean isQualifyingPlayerKill(Player victim, Player killer) {
        if (!isVanguard(killer) || victim.equals(killer)) return false;
        EntityDamageEvent cause = victim.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent hit) {
            Entity damager = hit.getDamager();
            return damager.equals(killer)
                    || damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                    && shooter.equals(killer);
        }
        if (cause == null) return false;
        return switch (cause.getCause()) {
            case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS ->
                    plugin.combatLogs() != null && killer.getUniqueId().equals(plugin.combatLogs().killerFor(victim));
            default -> false;
        };
    }

    private void applyAscendantStrength(Player player, int level) {
        int ticks = Math.max(40, plugin.getConfig().getInt("vanguard.ultimate.ascendant-strength-refresh-seconds", 60) * 20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, Math.max(0, level - 1), false, true, true), true);
    }

    private boolean eligibleCombatDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK || cause == DamageCause.PROJECTILE
                || cause == DamageCause.POISON || cause == DamageCause.WITHER || cause == DamageCause.MAGIC
                || cause == DamageCause.DRAGON_BREATH || cause == DamageCause.THORNS;
    }

    private LivingEntity attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player player) return player;
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living) return living;
            if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof LivingEntity living) return living;
            if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof LivingEntity living) return living;
        }
        DamageCause cause = event.getCause();
        boolean attributableStatus = cause == DamageCause.POISON || cause == DamageCause.WITHER
                || cause == DamageCause.MAGIC || cause == DamageCause.DRAGON_BREATH
                || cause == DamageCause.THORNS;
        if (attributableStatus && event.getEntity() instanceof Player victim && System.currentTimeMillis()
                - recentPlayerAttackAt.getOrDefault(victim.getUniqueId(), 0L) <= plugin.getConfig().getLong("combat.timeout-seconds", 15) * 1000L) {
            UUID attacker = recentPlayerAttacker.get(victim.getUniqueId());
            if (attacker != null) return plugin.getServer().getPlayer(attacker);
        }
        return null;
    }
    private boolean isVanguard(Player player) { return progress.get(player.getUniqueId()).vanguard; }
    private boolean isSword(ItemStack item) { return item != null && item.getType().name().endsWith("_SWORD"); }
    private boolean isVanguardWeapon(ItemStack item) {
        return item != null && (item.getType().name().endsWith("_SWORD") || item.getType().name().endsWith("_AXE"));
    }
    private long counterWindowMillis(VanguardProgression.Upgrade upgrade) {
        return VanguardRules.bloodthirstWindowMillis(upgrade);
    }
}
