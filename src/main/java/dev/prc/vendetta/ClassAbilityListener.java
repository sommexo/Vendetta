package dev.prc.vendetta;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
    private static final long COMBAT_MS = 15_000L;
    private final mark plugin;
    private final ClassProgression progression;
    private final Map<UUID, Long> predatorArmedUntil = new HashMap<>();
    private final Map<UUID, Long> predatorCooldown = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> recentAttacker = new HashMap<>();
    private final Map<UUID, Long> recentAttackerAt = new HashMap<>();
    private final Map<UUID, Long> lastAttackAt = new HashMap<>();
    private final Map<UUID, Long> invisibilityReturnAt = new HashMap<>();
    private final Set<UUID> activeVoids = new HashSet<>();
    private final Map<UUID, PotionEffect> previousInvisibility = new HashMap<>();
    private final Map<UUID, PotionEffect> previousSpeed = new HashMap<>();
    private final Map<UUID, Long> stunUntil = new HashMap<>();
    private final Map<UUID, Float> previousWalkSpeed = new HashMap<>();

    ClassAbilityListener(mark plugin, ClassProgression progression) {
        this.plugin = plugin;
        this.progression = progression;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
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
        voidBonusUsed.remove(id);
        voidBonusExpiry.put(id, now + 30_000L);
        PotionEffect invis = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (invis != null) previousInvisibility.put(id, invis);
        if (speed != null) previousSpeed.put(id, speed);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, true), true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40,
                tier == VanguardProgression.Upgrade.PERFECT || tier == VanguardProgression.Upgrade.ASCENDANT ? 1 : 0,
                false, true, true), true);
        combatUntil.put(id, now + COMBAT_MS);
        lastAttackAt.put(id, now);
        player.sendMessage(plugin.message("&5Into the Void activated."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        long now = System.currentTimeMillis();
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = attacker(event);
        if (victim != null && attacker != null && attacker != victim) {
            if (event instanceof EntityDamageByEntityEvent) {
                recentAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
                recentAttackerAt.put(victim.getUniqueId(), now);
            }
            combatUntil.put(victim.getUniqueId(), now + COMBAT_MS);
            combatUntil.put(attacker.getUniqueId(), now + COMBAT_MS);
            if ("phantom".equals(plugin.selectedClass(victim.getUniqueId()))) {
                int seconds = switch (progression.tier(victim.getUniqueId(), "marked_prey")) {
                    case DORMANT -> 6; case IMPERFECT -> 9; case PERFECT -> 15; case ASCENDANT -> 15;
                };
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, seconds * 20, 0, false, true, true));
                if (event instanceof EntityDamageByEntityEvent && victim.isSneaking()
                        && predatorArmedUntil.getOrDefault(victim.getUniqueId(), 0L) >= now) {
                    predatorArmedUntil.remove(victim.getUniqueId());
                    predatorCooldown.put(victim.getUniqueId(), now + 20_000L);
                    progression.cooldown(victim.getUniqueId(), "predators_instinct", now + 20_000L);
                    try { progression.save(victim.getUniqueId()); }
                    catch (RuntimeException ex) { plugin.getLogger().severe("Could not save Predator's Instinct cooldown: " + ex.getMessage()); }
                    event.setDamage(0);
                    event.setCancelled(true);
                    predatorCounter(victim, attacker);
                }
            }
        }
        if (eligibleCombatDamage(event)) {
            if (attacker != null && plugin.isBossMob(event.getEntity()))
                combatUntil.put(attacker.getUniqueId(), now + COMBAT_MS);
            if (victim != null && plugin.isBossAttack(event))
                combatUntil.put(victim.getUniqueId(), now + COMBAT_MS);
        }
        if (victim != null && activeVoids.contains(victim.getUniqueId())) {
            lastAttackAt.put(victim.getUniqueId(), now);
            combatUntil.put(victim.getUniqueId(), now + COMBAT_MS);
        }
        if (attacker != null && victim != null && attacker != victim && activeVoids.contains(attacker.getUniqueId())) {
            lastAttackAt.put(attacker.getUniqueId(), now);
            combatUntil.put(attacker.getUniqueId(), now + COMBAT_MS);
            attacker.removePotionEffect(PotionEffectType.INVISIBILITY);
            invisibilityReturnAt.put(attacker.getUniqueId(), now + 3000L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (activeVoids.contains(attacker.getUniqueId()) && combatUntil.getOrDefault(attacker.getUniqueId(), 0L) > System.currentTimeMillis())
                    refreshVoid(attacker, true);
            }, 60L);
        }
        if (attacker != null && plugin.isBossMob(event.getEntity()) && activeVoids.contains(attacker.getUniqueId())) {
            lastAttackAt.put(attacker.getUniqueId(), now);
            combatUntil.put(attacker.getUniqueId(), now + COMBAT_MS);
        }
        if (attacker != null && victim != null && attacker != victim && activeVoids.contains(attacker.getUniqueId())) {
            VanguardProgression.Upgrade tier = progression.tier(attacker.getUniqueId(), "into_the_void");
            UUID id = attacker.getUniqueId();
            if (tier == VanguardProgression.Upgrade.ASCENDANT && event instanceof EntityDamageByEntityEvent
                    && !progressionUsedVoidBonus(id)) {
                if (attacker.getHealth() < 20.0) event.setDamage(event.getDamage() * 1.5);
                markVoidBonusUsed(id);
            }
        }
    }

    private final Set<UUID> voidBonusUsed = new HashSet<>();
    private final Map<UUID, Long> voidBonusExpiry = new HashMap<>();
    private boolean progressionUsedVoidBonus(UUID id) { return voidBonusUsed.contains(id) || voidBonusExpiry.getOrDefault(id, 0L) < System.currentTimeMillis(); }
    private void markVoidBonusUsed(UUID id) { voidBonusUsed.add(id); }

    private void predatorCounter(Player phantom, Player attacker) {
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, true, true));
        UUID id = attacker.getUniqueId();
        if (!previousWalkSpeed.containsKey(id)) previousWalkSpeed.put(id, attacker.getWalkSpeed());
        attacker.setWalkSpeed(0f);
        stunUntil.put(id, System.currentTimeMillis() + 5000L);
        boolean mace = java.util.Arrays.stream(phantom.getInventory().getContents())
                .anyMatch(item -> item != null && item.getType() == Material.MACE);
        ItemStack cursor = phantom.getItemOnCursor();
        mace |= cursor != null && cursor.getType() == Material.MACE;
        Location destination = mace ? safeAbove(attacker) : null;
        boolean teleported = destination != null && phantom.teleport(destination);
        if (!teleported) {
            destination = safeBehind(attacker);
            if (destination != null && !phantom.teleport(destination))
                phantom.sendMessage(plugin.message("&cPredator's Instinct triggered, but no safe teleport destination was available."));
        }
        if (progression.tier(phantom.getUniqueId(), "into_the_void") == VanguardProgression.Upgrade.ASCENDANT)
            phantom.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 240, 2, false, true, true));
    }

    private Location safeAbove(Player attacker) {
        Location base = attacker.getLocation().clone().add(0, 2.5, 0);
        if (base.getY() >= base.getWorld().getMaxHeight() - 1) return null;
        return base.getBlock().isPassable() && base.clone().add(0, 1, 0).getBlock().isPassable() ? base : null;
    }

    private Location safeBehind(Player attacker) {
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
        for (UUID id : Set.copyOf(activeVoids)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || combatUntil.getOrDefault(id, 0L) <= now) {
                if (player != null) finishVoid(player); else activeVoids.remove(id);
                continue;
            }
            refreshVoid(player, false);
            if (progression.tier(id, "into_the_void") == VanguardProgression.Upgrade.ASCENDANT
                    && now - lastAttackAt.getOrDefault(id, now) > 30_000L)
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 3, false, true, true), true);
            if (voidBonusExpiry.getOrDefault(id, 0L) < now) voidBonusUsed.add(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStunnedMove(PlayerMoveEvent event) {
        if (stunUntil.getOrDefault(event.getPlayer().getUniqueId(), 0L) <= System.currentTimeMillis() || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) event.setTo(event.getFrom());
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
        int speed = progression.tier(player.getUniqueId(), "into_the_void") == VanguardProgression.Upgrade.PERFECT
                || progression.tier(player.getUniqueId(), "into_the_void") == VanguardProgression.Upgrade.ASCENDANT ? 1 : 0;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, speed, false, true, true), true);
        UUID id = player.getUniqueId();
        long returnAt = invisibilityReturnAt.getOrDefault(id, 0L);
        if (restoreInvisibility || returnAt == 0L || returnAt <= System.currentTimeMillis()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, true), true);
            invisibilityReturnAt.remove(id);
        }
    }

    private void finishVoid(Player player) {
        UUID id = player.getUniqueId();
        activeVoids.remove(id); combatUntil.remove(id); lastAttackAt.remove(id); invisibilityReturnAt.remove(id);
        player.removePotionEffect(PotionEffectType.INVISIBILITY); player.removePotionEffect(PotionEffectType.SPEED);
        PotionEffect invis = previousInvisibility.remove(id); if (invis != null && invis.getDuration() > 0) player.addPotionEffect(invis);
        PotionEffect speed = previousSpeed.remove(id); if (speed != null && speed.getDuration() > 0) player.addPotionEffect(speed);
        player.sendMessage(plugin.message("&7Into the Void ended."));
    }

    private Player attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = byEntity.getDamager();
            if (source instanceof Player player) return player;
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
            if (source instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
            if (source instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
            if (source instanceof LivingEntity living && living.getLastDamageCause() instanceof EntityDamageByEntityEvent nested
                    && nested.getDamager() instanceof Player player) return player;
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

    @EventHandler public void onJoin(PlayerJoinEvent event) { applyWardenResistance(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recentAttacker.remove(id); recentAttackerAt.remove(id);
        if (activeVoids.contains(id)) finishVoid(event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); recentAttacker.remove(id); recentAttackerAt.remove(id);
        if (activeVoids.contains(id)) finishVoid(event.getPlayer());
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId(); recentAttacker.remove(id); recentAttackerAt.remove(id);
        if (activeVoids.contains(id)) finishVoid(event.getEntity());
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
