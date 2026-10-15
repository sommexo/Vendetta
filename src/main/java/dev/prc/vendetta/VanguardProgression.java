package dev.prc.vendetta;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** UUID keyed persistent Vanguard progression and cooldown state. */
final class VanguardProgression {
    enum Upgrade { DORMANT, IMPERFECT, PERFECT, ASCENDANT }
    static final class PlayerData {
        boolean vanguard;
        Upgrade berserker = Upgrade.DORMANT;
        Upgrade headweb = Upgrade.DORMANT;
        Upgrade ultimate = Upgrade.DORMANT;
        long ultimateReadyAt;
        long headwebReadyAt;
        int killStreak;
    }

    private final File file;
    private final YamlConfiguration yaml;
    private final Map<UUID, PlayerData> players = new HashMap<>();

    VanguardProgression(File dataFolder) {
        file = new File(dataFolder, "vanguard-data.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        int schema = yaml.getInt("_schema-version", 0);
        if (schema > 1) throw new IllegalStateException("vanguard-data.yml was written by a newer VenMark version (schema " + schema + ")");
        for (String id : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                String root = id + ".";
                PlayerData data = new PlayerData();
                data.vanguard = yaml.getBoolean(root + "vanguard", false);
                data.berserker = readUpgrade(root + "abilities.berserker");
                data.headweb = readUpgrade(root + "abilities.headweb");
                data.ultimate = readUpgrade(root + "abilities.ultimate");
                data.ultimateReadyAt = yaml.getLong(root + "cooldowns.ultimate");
                data.headwebReadyAt = yaml.getLong(root + "cooldowns.headweb");
                data.killStreak = Math.max(0, yaml.getInt(root + "kill-streak"));
                players.put(uuid, data);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    PlayerData get(UUID uuid) { return players.computeIfAbsent(uuid, ignored -> new PlayerData()); }
    void save(UUID uuid) {
        yaml.set("_schema-version", 1);
        yaml.set("_config-version", 1);
        PlayerData data = get(uuid);
        String root = uuid + ".";
        yaml.set(root + "vanguard", data.vanguard);
        yaml.set(root + "abilities.berserker", data.berserker.name());
        yaml.set(root + "abilities.headweb", data.headweb.name());
        yaml.set(root + "abilities.ultimate", data.ultimate.name());
        yaml.set(root + "cooldowns.ultimate", data.ultimateReadyAt);
        yaml.set(root + "cooldowns.headweb", data.headwebReadyAt);
        yaml.set(root + "kill-streak", data.killStreak);
        try { yaml.save(file); } catch (IOException ex) { throw new IllegalStateException("Could not save Vanguard progression", ex); }
    }
    void saveAll() { for (UUID uuid : players.keySet()) save(uuid); }
    private Upgrade readUpgrade(String path) {
        try { return Upgrade.valueOf(yaml.getString(path, "DORMANT").toUpperCase()); }
        catch (IllegalArgumentException ex) { return Upgrade.DORMANT; }
    }
}
