package dev.prc.vendetta;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** UUID-keyed Phantom and Warden ability tiers and cooldown timestamps. */
final class ClassProgression {
    private final File file;
    private final YamlConfiguration yaml;
    private final Map<UUID, Map<String, VanguardProgression.Upgrade>> tiers = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();


    ClassProgression(File dataFolder) {
        file = new File(dataFolder, "class-abilities.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                for (String ability : new String[]{"predators_instinct", "marked_prey", "into_the_void", "afterimage",
                        "unshaken", "sculk_pulse", "ancient_guardian", "unyielding"}) {
                    String raw = yaml.getString(id + ".abilities." + ability, "DORMANT");
                    try { tier(uuid, ability, VanguardProgression.Upgrade.valueOf(raw)); }
                    catch (IllegalArgumentException ignored) { }
                    cooldown(uuid, ability, yaml.getLong(id + ".cooldowns." + ability));
                }
            } catch (IllegalArgumentException ignored) { }
        }
    }

    VanguardProgression.Upgrade tier(UUID uuid, String ability) {
        return tiers.computeIfAbsent(uuid, ignored -> new HashMap<>())
                .getOrDefault(ability, VanguardProgression.Upgrade.DORMANT);
    }

    void tier(UUID uuid, String ability, VanguardProgression.Upgrade value) {
        tiers.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(ability, value);
    }

    long cooldown(UUID uuid, String ability) {
        return cooldowns.computeIfAbsent(uuid, ignored -> new HashMap<>()).getOrDefault(ability, 0L);
    }

    void cooldown(UUID uuid, String ability, long value) {
        cooldowns.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(ability, value);
    }

    void save(UUID uuid) {
        yaml.set("_schema-version", 1);
        for (Map.Entry<String, VanguardProgression.Upgrade> entry : tiers.computeIfAbsent(uuid, ignored -> new HashMap<>()).entrySet())
            yaml.set(uuid + ".abilities." + entry.getKey(), entry.getValue().name());
        for (Map.Entry<String, Long> entry : cooldowns.computeIfAbsent(uuid, ignored -> new HashMap<>()).entrySet())
            yaml.set(uuid + ".cooldowns." + entry.getKey(), entry.getValue());
        try { yaml.save(file); }
        catch (IOException ex) { throw new IllegalStateException("Could not save class ability progression", ex); }
    }

    void saveAll() {
        java.util.Set<UUID> uuids = new java.util.HashSet<>(tiers.keySet());
        uuids.addAll(cooldowns.keySet());
        for (UUID uuid : uuids) save(uuid);
    }
}
