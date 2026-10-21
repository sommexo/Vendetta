package dev.prc.vendetta;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Loads plugin state without treating unreadable existing files as empty state. */
final class SafeYaml {
    private SafeYaml() { }

    static YamlConfiguration load(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) return yaml;
        // A zero-byte YAML document is a valid empty document. Log it because it may
        // indicate a prior interrupted write, while allowing unrelated plugin state
        // and command registration to initialize normally.
        if (file.length() == 0) {
            java.util.logging.Logger.getLogger("VenMark").warning(
                    "State file " + file.getName() + " is empty; no saved entries can be recovered from it.");
            return yaml;
        }
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException ex) {
            throw new IllegalStateException("Refusing to replace unreadable " + file.getName(), ex);
        }
    }

    static void saveAtomically(YamlConfiguration yaml, File file) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try { Files.deleteIfExists(temporary.toPath()); }
            catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
}
