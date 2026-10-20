package dev.prc.vendetta;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ancient Guardian fields, capture state, shared cards, and Unyielding. */
final class WardenUltimateService implements Listener {
    private static final long COMBAT_TIMEOUT = 15_000L;
    private static final int[] EFFECT_SECONDS = {3, 2, 1};
    private final mark plugin;
    private final ClassProgression progression;
    private final Map<UUID, Ultimate> ultimates = new HashMap<>();
    private final Map<UUID, UUID> capturedBy = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> recentAttacker = new HashMap<>();
    private final Map<UUID, Long> recentAttackerAt = new HashMap<>();
    private final Set<UUID> unyielding = new HashSet<>();

    WardenUltimateService(mark plugin, ClassProgression progression) {
        this.plugin = plugin;
        this.progression = progression;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.isRelicDisabled(event.getPlayer().getUniqueId(), "ancient_guardian")) return;
        if (!ActivationRules.primaryHand(event.getHand()) || !event.getAction().isLeftClick()
                || event.getItem() == null || event.getItem().getType() != Material.SHIELD
                || !"warden".equals(plugin.selectedClass(event.getPlayer().getUniqueId()))) return;
        event.setCancelled(true);
        activate(event.getPlayer());
    }

    private void activate(Player warden) {
        UUID id = warden.getUniqueId();
        Ultimate active = ultimates.get(id);
        if (active != null) {
            active.cards = 0;
            plugin.updateSidebar(warden);
            active.cycle = 0;
            active.combatUntil = System.currentTimeMillis() + COMBAT_TIMEOUT;
            applyPulse(active);
            warden.sendMessage(plugin.message("&3Ancient Guardian refreshed; cards reset."));
            return;
        }
        long now = System.currentTimeMillis();
        long ready = progression.cooldown(id, "ancient_guardian");
        if (ready > now && !plugin.opCooldownBypass(warden)) {
            warden.sendMessage(plugin.message("&cAncient Guardian ready in " + ((ready - now + 999) / 1000) + "s."));
            return;
        }
        VanguardProgression.Upgrade tier = progression.tier(id, "ancient_guardian");
        double radius = WardenRules.radius(tier);
        progression.cooldown(id, "ancient_guardian", now + 900_000L);
        try { progression.save(id); }
        catch (RuntimeException ex) {
            progression.cooldown(id, "ancient_guardian", ready);
            warden.sendMessage(plugin.message("&cAncient Guardian could not save its cooldown."));
            return;
        }
        Ultimate created = new Ultimate(id, warden.getLocation().clone(), radius, now);
        created.supporters.add(id);
        for (UUID ally : plugin.alliesOf(id))
            if ("warden".equals(plugin.selectedClass(ally))) created.supporters.add(ally);

        // One activation from each opposing side cancels one another. Any extra state remains active.
        Ultimate opposing = ultimates.values().stream()
                .filter(other -> !sameSide(other.owner, id) && other.center.getWorld().equals(created.center.getWorld()))
                .findFirst().orElse(null);
        if (opposing != null) {
            end(opposing, "&cAncient Guardian was cancelled by an opposing Warden.");
            warden.sendMessage(plugin.message("&cYour Ancient Guardian was cancelled by the opposing Warden."));
            return;
        }
        ultimates.put(id, created);
        plugin.updateSidebar(warden);
        combatUntil.put(id, now + COMBAT_TIMEOUT);
        applyPulse(created);
        warden.sendMessage(plugin.message("&3Ancient Guardian activated (" + (int) radius + " blocks)."));
    }

    private boolean sameSide(UUID first, UUID second) {
        return first.equals(second) || plugin.isAlly(first, second) || plugin.isAlly(second, first);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Ultimate ultimate : List.copyOf(ultimates.values())) {
            boolean supportOnline = ultimate.supporters.stream().anyMatch(id -> plugin.getServer().getPlayer(id) != null);
            if (!supportOnline || combatUntil.getOrDefault(ultimate.owner, ultimate.combatUntil) <= now) {
                end(ultimate, "&7Ancient Guardian ended.");
                continue;
            }
            ultimate.cycle++;
            applyPulse(ultimate);
            captureEnemies(ultimate);
            Player owner = plugin.getServer().getPlayer(ultimate.owner);
            if (owner != null && progression.tier(ultimate.owner, "ancient_guardian") == VanguardProgression.Upgrade.ASCENDANT
                    && owner.getHealth() <= 10.0) unyielding.add(ultimate.owner);
            if (owner != null && unyielding.contains(ultimate.owner)) applyUnyieldingDamageReduction(owner);
        }
        for (var entry : List.copyOf(capturedBy.entrySet())) {
            Player captive = plugin.getServer().getPlayer(entry.getKey());
            if (captive == null) { capturedBy.remove(entry.getKey()); continue; }
            Ultimate ultimate = ultimates.get(entry.getValue());
            Location center = ultimate == null ? null : ultimate.center;
            if (ultimate == null || !ultimate.capturedEnemies.contains(entry.getKey())
                    || !captive.getWorld().equals(center.getWorld())
                    || !WardenRules.withinCaptureEscapeRadius(captive.getLocation().distanceSquared(center))) release(entry.getKey());
        }
        for (UUID id : List.copyOf(unyielding)) {
            if (!ultimates.containsKey(id)) { unyielding.remove(id); continue; }
            Player warden = plugin.getServer().getPlayer(id);
            if (warden != null) { warden.setWalkSpeed(0f); warden.setSprinting(false); }
        }
    }

    private void applyPulse(Ultimate ultimate) {
        Player owner = plugin.getServer().getPlayer(ultimate.owner);
        int duration = WardenRules.effectSeconds(ultimate.cycle) * 20;
        if (owner != null) effect(owner, true, duration);
        for (UUID allyId : plugin.alliesOf(ultimate.owner)) {
            Player ally = plugin.getServer().getPlayer(allyId);
            if (ally != null && ally.getWorld().equals(ultimate.center.getWorld())
                    && ally.getLocation().distanceSquared(ultimate.center) <= ultimate.radius * ultimate.radius) {
                ultimate.affectedAllies.add(allyId);
                effect(ally, true, duration);
            }
        }
        for (Player enemy : ultimate.center.getWorld().getPlayers()) {
            if (sameSide(ultimate.owner, enemy.getUniqueId()) || enemy.equals(owner)) continue;
            boolean inside = enemy.getLocation().distanceSquared(ultimate.center) <= ultimate.radius * ultimate.radius;
            boolean held = ultimate.capturedEnemies.contains(enemy.getUniqueId());
            if (inside || held) effect(enemy, false, duration);
        }
    }

    private void effect(Player player, boolean ally, int duration) {
        player.addPotionEffect(new PotionEffect(ally ? PotionEffectType.RESISTANCE : PotionEffectType.WEAKNESS,
                duration, ally ? 1 : 0, false, true, true), true);
    }

    private void captureEnemies(Ultimate ultimate) {
        for (Player enemy : ultimate.center.getWorld().getPlayers()) {
            if (sameSide(ultimate.owner, enemy.getUniqueId()) || enemy.getUniqueId().equals(ultimate.owner)) continue;
            if (enemy.getLocation().distanceSquared(ultimate.center) > ultimate.radius * ultimate.radius) continue;
            if (capturedBy.containsKey(enemy.getUniqueId())) continue;
            ultimate.capturedEnemies.add(enemy.getUniqueId());
            capturedBy.put(enemy.getUniqueId(), ultimate.owner);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = attacker(event);
        long now = System.currentTimeMillis();
        if (victim != null && event instanceof EntityDamageByEntityEvent && attacker != null && attacker != victim) {
            recentAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
            recentAttackerAt.put(victim.getUniqueId(), now);
        }
        if (attacker != null && victim != null && attacker != victim) {
            combatUntil.put(attacker.getUniqueId(), now + COMBAT_TIMEOUT);
            combatUntil.put(victim.getUniqueId(), now + COMBAT_TIMEOUT);
            if (!isAllyOfAnyActiveOwner(attacker.getUniqueId(), victim.getUniqueId())) cardAndDamage(attacker, victim, event);
            for (Ultimate ultimate : ultimates.values()) {
                if (sameSide(ultimate.owner, attacker.getUniqueId()) || sameSide(ultimate.owner, victim.getUniqueId()))
                    ultimate.combatUntil = now + COMBAT_TIMEOUT;
            }
            for (Ultimate ultimate : ultimates.values()) combatUntil.put(ultimate.owner, ultimate.combatUntil);
        }
        if (victim != null && plugin.isBossAttack(event)) {
            combatUntil.put(victim.getUniqueId(), now + COMBAT_TIMEOUT);
            refreshCombatForSide(victim.getUniqueId(), now);
        }
        if (attacker != null && plugin.isBossMob(event.getEntity())) {
            combatUntil.put(attacker.getUniqueId(), now + COMBAT_TIMEOUT);
            refreshCombatForSide(attacker.getUniqueId(), now);
        }
        if (victim != null && unyielding.contains(victim.getUniqueId())) {
            event.setDamage(event.getDamage() * 0.40);
            return;
        }
        if (victim != null) {
            Ultimate active = ultimates.get(victim.getUniqueId());
            if (active != null && progression.tier(victim.getUniqueId(), "ancient_guardian") == VanguardProgression.Upgrade.ASCENDANT
                    && victim.getHealth() - (event.getFinalDamage() * 0.40) <= 10.0) {
                unyielding.add(victim.getUniqueId());
                event.setDamage(event.getDamage() * 0.40);
            }
        }
    }

    private boolean isAllyOfAnyActiveOwner(UUID attacker, UUID victim) {
        for (Ultimate ultimate : ultimates.values()) if (sameSide(ultimate.owner, attacker) && sameSide(ultimate.owner, victim)) return true;
        return false;
    }

    private void cardAndDamage(Player attacker, Player victim, EntityDamageEvent event) {
        for (Ultimate ultimate : ultimates.values()) {
            if (!sameSide(ultimate.owner, attacker.getUniqueId()) || sameSide(ultimate.owner, victim.getUniqueId())) continue;
            double multiplier = WardenRules.cardMultiplier(ultimate.cards);
            event.setDamage(event.getDamage() * multiplier);
            if (event.getDamage() > 0) {
                ultimate.cards = Math.min(50, ultimate.cards + 1);
                Player owner = plugin.getServer().getPlayer(ultimate.owner);
                if (owner != null) plugin.updateSidebar(owner);
            }
        }
    }

    private void refreshCombatForSide(UUID participant, long now) {
        for (Ultimate ultimate : ultimates.values()) {
            if (sameSide(ultimate.owner, participant)) ultimate.combatUntil = now + COMBAT_TIMEOUT;
        }
    }

    private Player attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = byEntity.getDamager();
            if (source instanceof Player player) return player;
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
            if (source instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
            if (source instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        }
        if (event.getEntity() instanceof Player victim && System.currentTimeMillis()
                - recentAttackerAt.getOrDefault(victim.getUniqueId(), 0L) <= COMBAT_TIMEOUT) {
            UUID id = switch (event.getCause()) {
                case POISON, WITHER, MAGIC, DRAGON_BREATH, THORNS -> recentAttacker.get(victim.getUniqueId());
                default -> null;
            };
            return id == null ? null : plugin.getServer().getPlayer(id);
        }
        return null;
    }

    private void applyUnyieldingDamageReduction(Player warden) {
        // The listener applies the reduction to each incoming event; this method marks the active state.
        warden.setWalkSpeed(0f);
    }

    private void end(Ultimate ultimate, String message) {
        if (ultimates.remove(ultimate.owner) == null) return;
        combatUntil.remove(ultimate.owner);
        for (UUID id : List.copyOf(ultimate.capturedEnemies)) release(id);
        unyielding.remove(ultimate.owner);
        Player owner = plugin.getServer().getPlayer(ultimate.owner);
        if (owner != null) {
            owner.setWalkSpeed(0.2f);
            plugin.updateSidebar(owner);
            owner.sendMessage(plugin.message(message));
        }
    }

    int cards(UUID owner) {
        Ultimate active = ultimates.get(owner);
        return active == null ? 0 : active.cards;
    }

    private void release(UUID captive) { capturedBy.remove(captive); }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        recentAttacker.remove(id); recentAttackerAt.remove(id);
        Ultimate owned = ultimates.get(id);
        if (owned != null && unyielding.contains(id)) {
            for (UUID allyId : Set.copyOf(owned.affectedAllies)) {
                if (allyId.equals(id)) continue;
                Player ally = plugin.getServer().getPlayer(allyId);
                if (ally == null || ally.isDead()) continue;
                plugin.suppressNextPvpSettlement(allyId);
                ally.setHealth(0.0);
            }
        }
        for (Ultimate ultimate : List.copyOf(ultimates.values())) {
            if (!ultimate.supporters.contains(id)) continue;
            ultimate.supporters.remove(id);
            if (ultimate.supporters.isEmpty()) end(ultimate, "&cAncient Guardian fell with its last Warden supporter.");
        }
        release(id);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recentAttacker.remove(id); recentAttackerAt.remove(id);
        Ultimate own = ultimates.get(id);
        if (own != null) end(own, "&7Ancient Guardian ended as its Warden left.");
        release(id);
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recentAttacker.remove(id); recentAttackerAt.remove(id);
        Ultimate own = ultimates.get(id);
        if (own != null) end(own, "&7Ancient Guardian ended after a world change.");
        release(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (unyielding.contains(event.getPlayer().getUniqueId()) && event.getTo() != null
                && (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ())) event.setTo(event.getFrom());
    }

    @EventHandler(ignoreCancelled = true) public void onInteractBlocked(PlayerInteractEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onUnyieldingAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && unyielding.contains(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onConsume(PlayerItemConsumeEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onInventory(InventoryClickEvent event) { if (unyielding.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onInventory(InventoryDragEvent event) { if (unyielding.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent event) { if (unyielding.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    void shutdown() {
        for (Ultimate ultimate : List.copyOf(ultimates.values())) end(ultimate, "&7Ancient Guardian ended as the server stopped.");
        capturedBy.clear();
        unyielding.clear();
    }

    void classChanged(Player player) {
        UUID id = player.getUniqueId();
        Ultimate own = ultimates.get(id);
        if (own != null) end(own, "&7Ancient Guardian ended after a class change.");
        for (Ultimate ultimate : List.copyOf(ultimates.values())) {
            if (!ultimate.supporters.contains(id)) continue;
            ultimate.supporters.remove(id);
            if (ultimate.supporters.isEmpty()) end(ultimate, "&7Ancient Guardian ended after its last Warden changed class.");
        }
        unyielding.remove(id);
    }

    private static final class Ultimate {
        final UUID owner;
        final Location center;
        final double radius;
        final Set<UUID> capturedEnemies = new HashSet<>();
        final Set<UUID> affectedAllies = new HashSet<>();
        final Set<UUID> supporters = new HashSet<>();
        int cards;
        int cycle;
        long combatUntil;
        Ultimate(UUID owner, Location center, double radius, long now) {
            this.owner = owner; this.center = center; this.radius = radius; this.combatUntil = now + COMBAT_TIMEOUT;
        }
    }
}
