package dev.prc.vendetta;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side Phantom abilities and Warden regular abilities. */
final class ClassAbilityListener implements Listener {
    private static final long COMBAT_MS = PhantomRules.INTO_THE_VOID_INACTIVITY_MILLIS;
    private final mark plugin;
    private final ClassProgression progression;
    private final Map<UUID, Long> predatorArmedUntil = new HashMap<>();
    private final Map<UUID, Long> predatorCooldown = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> recentAttacker = new HashMap<>();
    private final Map<UUID, Long> recentAttackerAt = new HashMap<>();
    private final Map<UUID, Long> voidActivationEndsAt = new HashMap<>();
    private final Map<UUID, Long> ascendantSpeedUntil = new HashMap<>();
    private final Set<UUID> ascendantSpeedUsed = new HashSet<>();
    private final Set<UUID> activeVoids = new HashSet<>();
    private final Map<UUID, Long> voidActivationIds = new HashMap<>();
    private long nextVoidActivationId;
    private final Map<UUID, PotionEffect> previousInvisibility = new HashMap<>();
    private final Map<UUID, PotionEffect> previousSpeed = new HashMap<>();
    private final Map<UUID, Long> stunUntil = new HashMap<>();
    private final Map<UUID, Float> previousWalkSpeed = new HashMap<>();
    private final Map<UUID, Boolean> previousMobAi = new HashMap<>();
    private final Map<UUID, Long> mobStunUntil = new HashMap<>();
    private final Map<UUID, Long> knockupUntil = new HashMap<>();

    ClassAbilityListener(mark plugin, ClassProgression progression) {
        this.plugin = plugin;
        this.progression = progression;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (plugin.isRelicDisabled(player.getUniqueId())) return;
        if (!ActivationRules.primaryHand(event.getHand())) return;
        String clazz = plugin.selectedClass(player.getUniqueId());
        ItemStack item = event.getItem();
        if (clazz == null || item == null) return;
        Action action = event.getAction();
        if ("phantom".equals(clazz)) {
            if (action.isRightClick() && isSword(item)) armPredator(player);
            if (action.isLeftClick() && item.getType() == Material.ENDER_PEARL) {
                event.setCancelled(true);
                activateVoid(player);
            }
        } else if ("warden".equals(clazz) && action.isRightClick() && isSword(item)) {
            activateSculkPulse(player);
        }
    }

    private void armPredator(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long cooldown = Math.max(predatorCooldown.getOrDefault(id, 0L), progression.cooldown(id, "predators_instinct"));
        if ((!plugin.opCooldownBypass(player) && cooldown > now)
                || predatorArmedUntil.getOrDefault(id, 0L) > now) return;
        predatorArmedUntil.put(id, now + 1000L);
        player.sendMessage(plugin.message("&bPredator's Instinct armed"));
    }

    private void activateSculkPulse(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long ready = progression.cooldown(id, "sculk_pulse");
        if (ready > now && !plugin.opCooldownBypass(player)) return;
        progression.cooldown(id, "sculk_pulse", now + 20_000L);
        try { progression.save(id); }
        catch (RuntimeException ex) { progression.cooldown(id, "sculk_pulse", ready); return; }
        int count = 0;
        for (Player target : player.getWorld().getPlayers()) {
            if (target.equals(player) || plugin.isAlly(id, target.getUniqueId())
                    || target.getLocation().distanceSquared(player.getLocation()) > 400) continue;
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, true, true));
            count++;
        }
        player.sendMessage(plugin.message(count == 0 ? "&7Sculk Pulse found no enemies within 20 blocks."
                : "&3Sculk Pulse revealed " + count + " enemy player(s)."));
    }

    private void activateVoid(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long ready = progression.cooldown(id, "into_the_void");
        if (activeVoids.contains(id)) { finishVoid(player); return; }
        if (ready > now && !plugin.opCooldownBypass(player)) return;
        VanguardProgression.Upgrade tier = progression.tier(id, "into_the_void");
        progression.cooldown(id, "into_the_void", now + 300_000L);
        try { progression.save(id); }
        catch (RuntimeException ex) { progression.cooldown(id, "into_the_void", ready); return; }
        activeVoids.add(id);
        voidActivationIds.put(id, ++nextVoidActivationId);
        PotionEffect invis = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (invis != null) previousInvisibility.put(id, invis);
        if (speed != null) previousSpeed.put(id, speed);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, true), true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, true, true), true);
        voidActivationEndsAt.put(id, now + PhantomRules.intoTheVoidDurationMillis(tier));
        ascendantSpeedUntil.remove(id);
        ascendantSpeedUsed.remove(id);
        player.sendMessage(plugin.message("&5Into the Void activated."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        long now = System.currentTimeMillis();
        Player victim = event.getEntity() instanceof Player p ? p : null;
        LivingEntity damagedEntity = event.getEntity() instanceof LivingEntity living ? living : null;
        LivingEntity attacker = attacker(event);
        if (victim != null && attacker != null && attacker != victim) {
            if (attacker instanceof Player playerAttacker) {
                if (event instanceof EntityDamageByEntityEvent) {
                    recentAttacker.put(victim.getUniqueId(), playerAttacker.getUniqueId());
                    recentAttackerAt.put(victim.getUniqueId(), now);
                }
                combatUntil.put(victim.getUniqueId(), now + COMBAT_MS);
                combatUntil.put(playerAttacker.getUniqueId(), now + COMBAT_MS);
                if ("phantom".equals(plugin.selectedClass(victim.getUniqueId()))) {
                    int seconds = switch (progression.tier(victim.getUniqueId(), "marked_prey")) {
                        case DORMANT -> 6; case IMPERFECT -> 9; case PERFECT -> 15; case ASCENDANT -> 15;
                    };
                    playerAttacker.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, seconds * 20, 0, false, true, true));
                }
            }
            if ("phantom".equals(plugin.selectedClass(victim.getUniqueId()))
                    && event instanceof EntityDamageByEntityEvent
                    && predatorArmedUntil.getOrDefault(victim.getUniqueId(), 0L) >= now
                    && isEligibleCounterAttacker(attacker)) {
                predatorArmedUntil.remove(victim.getUniqueId());
                predatorCooldown.put(victim.getUniqueId(), now + 120_000L);
                progression.cooldown(victim.getUniqueId(), "predators_instinct", now + 120_000L);
                try { progression.save(victim.getUniqueId()); }
                catch (RuntimeException ex) { plugin.getLogger().severe("Could not save Predator's Instinct cooldown: " + ex.getMessage()); }
                event.setDamage(0);
                event.setCancelled(true);
                predatorCounter(victim, attacker);
            }
        }
        if (eligibleCombatDamage(event)) {
            if (attacker != null && plugin.isBossMob(event.getEntity()))
                combatUntil.put(attacker.getUniqueId(), now + COMBAT_MS);
            if (victim != null && plugin.isBossAttack(event))
                combatUntil.put(victim.getUniqueId(), now + COMBAT_MS);
        }
        // Any hit ends Into the Void immediately. Apply the outgoing hit bonus before cleanup.
        if (event instanceof EntityDamageByEntityEvent && attacker instanceof Player playerAttacker
                && damagedEntity != null && playerAttacker != damagedEntity
                && activeVoids.contains(playerAttacker.getUniqueId())) {
            UUID id = playerAttacker.getUniqueId();
            VanguardProgression.Upgrade tier = progression.tier(id, "into_the_void");
            double multiplier = PhantomRules.intoTheVoidNextHitMultiplier(tier);
            if (multiplier > 1.0) event.setDamage(event.getDamage() * multiplier);
            finishVoid(playerAttacker, false);
        }
        // Taking a hit also ends the ultimate immediately.
        if (victim != null && activeVoids.contains(victim.getUniqueId())) {
            finishVoid(victim, false);
        }
    }

    private void predatorCounter(Player phantom, LivingEntity attacker) {
        UUID phantomId = phantom.getUniqueId();
        long now = System.currentTimeMillis();
        VanguardProgression.Upgrade tier = progression.tier(phantomId, "predators_instinct");
        ItemStack held = phantom.getInventory().getItemInMainHand();
        boolean mace = held != null && held.getType() == Material.MACE;

        double stunSeconds;
        boolean blind;
        int launchHeight = 0;
        boolean grace = false;
        switch (tier) {
            case DORMANT -> { stunSeconds = 0.5; blind = false; }
            case IMPERFECT -> {
                stunSeconds = 3; blind = true;
                if (mace) launchHeight = 8;
            }
            case PERFECT, ASCENDANT -> {
                stunSeconds = mace ? 5 : 2.5;
                blind = mace;
                if (mace) { launchHeight = 12; grace = true; }
            }
            default -> { stunSeconds = 3; blind = true; }
        }

        if (blind) attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) (stunSeconds * 20), 0, false, true, true));
        if (attacker instanceof Player target) {
            UUID targetId = target.getUniqueId();
            if (!previousWalkSpeed.containsKey(targetId)) previousWalkSpeed.put(targetId, target.getWalkSpeed());
            target.setWalkSpeed(0f);
            stunUntil.merge(targetId, now + (long) (stunSeconds * 1000), Math::max);
        } else if (attacker instanceof Monster mob) {
            UUID mobId = mob.getUniqueId();
            previousMobAi.putIfAbsent(mobId, mob.hasAI());
            mob.setAI(false);
            long expiry = now + (long) (stunSeconds * 1000);
            mobStunUntil.merge(mobId, expiry, Math::max);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (mobStunUntil.getOrDefault(mobId, 0L) != expiry) return;
                mobStunUntil.remove(mobId);
                Boolean hadAi = previousMobAi.remove(mobId);
                if (hadAi != null && mob.isValid()) mob.setAI(hadAi);
            }, (long) Math.ceil(stunSeconds * 20));
        }

        if (tier == VanguardProgression.Upgrade.DORMANT || !mace || launchHeight == 0) {
            Location behind = safeBehind(attacker);
            if (behind != null) {
                behind.setPitch(phantom.getLocation().getPitch());
                phantom.teleport(behind);
            } else {
                phantom.sendMessage(plugin.message("&cPredator's Instinct triggered, but no safe teleport destination was available."));
            }
        } else {
            Location launch = attacker.getLocation();
            if (launch.getY() < launch.getWorld().getMaxHeight() - 2
                    && launch.clone().add(0, 1, 0).getBlock().isPassable()) {
                attacker.setVelocity(new Vector(0, launchHeight >= 12 ? 1.55 : 1.25, 0));
                attacker.setFallDistance(0);
                if (attacker instanceof Player target) knockupUntil.put(target.getUniqueId(), now + 1_200L);
                if (grace) {
                    phantom.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0, false, false, true), true);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (phantom.isOnline()) {
                            // Do not erase a newer slow-falling effect applied by another system.
                            PotionEffect current = phantom.getPotionEffect(PotionEffectType.SLOW_FALLING);
                            if (current != null && current.getDuration() <= 2) phantom.removePotionEffect(PotionEffectType.SLOW_FALLING);
                            phantom.setVelocity(phantom.getLocation().getDirection().multiply(0).setY(-0.08));
                        }
                    }, 40L);
                }
            } else {
                Location behind = safeBehind(attacker);
                if (behind != null) phantom.teleport(behind);
            }
        }

        if (tier == VanguardProgression.Upgrade.ASCENDANT
                || progression.tier(phantomId, "afterimage") == VanguardProgression.Upgrade.ASCENDANT) {
            phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phantom.getLocation().add(0, 1, 0),
                    24, 0.45, 0.7, 0.45, 0.02);
            final int[] ticks = {0};
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                if (!phantom.isOnline() || ticks[0]++ >= 20) { task.cancel(); return; }
                phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phantom.getLocation().add(0, 1, 0),
                        5, 0.25, 0.4, 0.25, 0.01);
            }, 1L, 1L);
        }
    }

    private Location safeAbove(LivingEntity attacker) {
        Location base = attacker.getLocation().clone().add(0, 2.5, 0);
        if (base.getY() >= base.getWorld().getMaxHeight() - 1) return null;
        return base.getBlock().isPassable() && base.clone().add(0, 1, 0).getBlock().isPassable() ? base : null;
    }

    private Location safeBehind(LivingEntity attacker) {
        Vector direction = attacker.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        Location base = attacker.getLocation().clone().subtract(direction.multiply(1.25));
        for (int up = 0; up <= 2; up++) {
            Location candidate = base.clone().add(0, up, 0);
            if (safe(candidate)) return candidate;
        }
        return null;
    }

    private boolean safe(Location location) {
        return location.getBlock().isPassable() && location.clone().add(0, 1, 0).getBlock().isPassable()
                && location.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : Set.copyOf(stunUntil.keySet())) if (stunUntil.get(id) <= now) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) player.setWalkSpeed(previousWalkSpeed.getOrDefault(id, 0.2f));
            stunUntil.remove(id); previousWalkSpeed.remove(id);
        }
        knockupUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        for (UUID id : Set.copyOf(activeVoids)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || voidActivationEndsAt.getOrDefault(id, 0L) <= now) {
                if (player != null) finishVoid(player, true);
                else {
                    activeVoids.remove(id);
                    voidActivationIds.remove(id);
                    voidActivationEndsAt.remove(id);
                    ascendantSpeedUntil.remove(id);
                    ascendantSpeedUsed.remove(id);
                    previousInvisibility.remove(id);
                    previousSpeed.remove(id);
                }
                continue;
            }
            refreshVoid(player, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStunnedMove(PlayerMoveEvent event) {
        if (stunUntil.getOrDefault(event.getPlayer().getUniqueId(), 0L) <= System.currentTimeMillis() || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()
                || (event.getFrom().getY() != event.getTo().getY()
                && knockupUntil.getOrDefault(event.getPlayer().getUniqueId(), 0L) <= System.currentTimeMillis())) {
            Location allowed = event.getFrom().clone();
            if (knockupUntil.getOrDefault(event.getPlayer().getUniqueId(), 0L) > System.currentTimeMillis())
                allowed.setY(event.getTo().getY());
            event.setTo(allowed);
        }
    }

    void shutdown() {
        for (Map.Entry<UUID, Float> entry : Map.copyOf(previousWalkSpeed).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) player.setWalkSpeed(entry.getValue());
        }
        previousWalkSpeed.clear(); stunUntil.clear();
        for (UUID id : Set.copyOf(activeVoids)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) finishVoid(player);
        }
    }

    void classChanged(Player player) {
        UUID id = player.getUniqueId();
        predatorArmedUntil.remove(id);
        if (activeVoids.contains(id)) finishVoid(player);
        applyWardenResistance(player);
    }

    private void refreshVoid(Player player, boolean restoreInvisibility) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        int speed = 0;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, speed, false, true, true), true);
        if (restoreInvisibility || activeVoids.contains(id)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, true), true);
        }
    }

    private void finishVoid(Player player) {
        finishVoid(player, false);
    }

    private void finishVoid(Player player, boolean naturalCombatEnd) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean grantAscendantEscape = naturalCombatEnd
                && progression.tier(id, "into_the_void") == VanguardProgression.Upgrade.ASCENDANT;
        activeVoids.remove(id); voidActivationIds.remove(id); combatUntil.remove(id);
        voidActivationEndsAt.remove(id); ascendantSpeedUntil.remove(id); ascendantSpeedUsed.remove(id);
        player.removePotionEffect(PotionEffectType.INVISIBILITY); player.removePotionEffect(PotionEffectType.SPEED);
        PotionEffect invis = previousInvisibility.remove(id); if (invis != null && invis.getDuration() > 0) player.addPotionEffect(invis);
        PotionEffect speed = previousSpeed.remove(id); if (speed != null && speed.getDuration() > 0) player.addPotionEffect(speed);
        if (grantAscendantEscape) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15 * 20, 3, false, true, true), true);
            player.sendMessage(plugin.message("&5Afterimage activated: Speed IV."));
        }
        player.sendMessage(plugin.message("&7Into the Void ended."));
    }

    private LivingEntity attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = byEntity.getDamager();
            if (source instanceof LivingEntity living) return living;
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living) return living;
            if (source instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof LivingEntity living) return living;
            if (source instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof LivingEntity living) return living;
        }
        if (event.getEntity() instanceof Player victim && System.currentTimeMillis()
                - recentAttackerAt.getOrDefault(victim.getUniqueId(), 0L) <= COMBAT_MS) {
            return switch (event.getCause()) {
                case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS -> plugin.getServer().getPlayer(recentAttacker.get(victim.getUniqueId()));
                default -> null;
            };
        }
        return null;
    }

    private boolean eligibleCombatDamage(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent || switch (event.getCause()) {
            case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS -> true;
            default -> false;
        };
    }

    private boolean isEligibleCounterAttacker(LivingEntity attacker) {
        return attacker instanceof Player || attacker instanceof Monster;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { applyWardenResistance(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPlayerStun(event.getPlayer());
        recentAttacker.remove(id); recentAttackerAt.remove(id);
        if (activeVoids.contains(id)) finishVoid(event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); recentAttacker.remove(id); recentAttackerAt.remove(id);
        clearPlayerStun(event.getPlayer());
        if (activeVoids.contains(id)) finishVoid(event.getPlayer());
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId(); recentAttacker.remove(id); recentAttackerAt.remove(id);
        clearPlayerStun(event.getEntity());
        if (activeVoids.contains(id)) finishVoid(event.getEntity());
    }

    private void clearPlayerStun(Player player) {
        UUID id = player.getUniqueId();
        stunUntil.remove(id);
        knockupUntil.remove(id);
        Float speed = previousWalkSpeed.remove(id);
        if (speed != null) player.setWalkSpeed(speed);
    }

    void applyWardenResistance(Player player) {
        if (!"warden".equals(plugin.selectedClass(player.getUniqueId()))) {
            var oldAttribute = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (oldAttribute != null && oldAttribute.getBaseValue() <= 0.40) oldAttribute.setBaseValue(0.0);
            return;
        }
        double resistance = switch (progression.tier(player.getUniqueId(), "unshaken")) {
            case DORMANT -> 0.20; case IMPERFECT -> 0.30; case PERFECT -> 0.40; case ASCENDANT -> 0.40;
        };
        var attribute = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attribute != null) attribute.setBaseValue(resistance);
    }

    private boolean isSword(ItemStack item) { return item.getType().name().endsWith("_SWORD"); }
}
