package dev.prc.vendetta;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/** Actual outcome of a wave attempt, including only entities that spawned and were recorded. */
record AngelWaveSpawnResult(boolean requestAccepted, boolean started, int requested, List<SpawnedAngel> angels) {
    AngelWaveSpawnResult {
        angels = List.copyOf(angels);
        if (requested < 0 || angels.size() > requested || started != !angels.isEmpty())
            throw new IllegalArgumentException("Inconsistent Angel wave spawn result");
    }

    int spawned() { return angels.size(); }

    List<String> coordinateLines() {
        List<String> lines = new ArrayList<>();
        for (SpawnedAngel angel : angels) {
            AngelSpawnBoundary.SpawnPosition position = angel.position();
            lines.add(String.format(Locale.ROOT, "&e%s Angel &f%s X %s Y %s Z %s", angel.type(), position.world(),
                    Double.toString(position.x()), Double.toString(position.y()), Double.toString(position.z())));
        }
        return List.copyOf(lines);
    }

    record SpawnedAngel(UUID entityId, String type, AngelSpawnBoundary.SpawnPosition position) {
        SpawnedAngel {
            if (entityId == null || type == null || type.isBlank() || position == null)
                throw new IllegalArgumentException("Missing Angel spawn type, identity, or coordinates");
        }
    }
}
