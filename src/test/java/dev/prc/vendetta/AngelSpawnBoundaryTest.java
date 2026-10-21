package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AngelSpawnBoundaryTest {
    @Test
    void centeredBorderUsesDirectionalOneAndHalfLimit() {
        assertTrue(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 300, 0));
        assertFalse(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 334, 0));
        assertTrue(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 0, -300));
    }

    @Test
    void offsetBorderUsesActualWorldSpawnAndDifferentDirectionalDistances() {
        assertTrue(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, 399, 0));
        assertFalse(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, 401, 0));
        assertTrue(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, -266, 0));
        assertFalse(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, -267, 0));
        assertTrue(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, 0, 299));
        assertFalse(AngelSpawnBoundary.contains(100, -50, 1_000, 0, 0, 0, 301));
    }

    @Test
    void borderSizeAndBoundaryEdgesAreRespected() {
        assertTrue(AngelSpawnBoundary.contains(-200, 300, 600, -200, 300, 0, 300));
        assertFalse(AngelSpawnBoundary.contains(-200, 300, 600, -200, 300, 1, 300));
        assertFalse(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 500, 0));
        assertFalse(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, -500, 0));
        assertFalse(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 0, 500));
        assertFalse(AngelSpawnBoundary.contains(0, 0, 1_000, 0, 0, 0, -500));
    }

    @Test
    void coordinateTrackingStoresActualSuccessfulCoordinatesOnly() {
        Map<UUID, AngelSpawnBoundary.SpawnPosition> locations = new HashMap<>();
        UUID entity = UUID.randomUUID();
        // Failed spawn attempts never call the success recorder and therefore create no entry.
        assertTrue(locations.isEmpty());
        AngelSpawnBoundary.recordSuccessfulSpawn(locations, entity, "world_nether", 12.25, 71.0, -8.75);
        assertEquals(new AngelSpawnBoundary.SpawnPosition("world_nether", 12.25, 71.0, -8.75), locations.get(entity));
        assertEquals(1, locations.size());
    }

    @Test
    void waveCandidatesMustBeSpacedFromAlreadySpawnedAngels() {
        var existing = java.util.List.of(new AngelSpawnBoundary.SpawnPosition("world", 10.5, 70, -4.5));
        assertFalse(AngelSpawnBoundary.separatedFromExisting("world", 20.5, -4.5, existing,
                AngelSpawnBoundary.MINIMUM_WAVE_SPAWN_SEPARATION));
        assertFalse(AngelSpawnBoundary.separatedFromExisting("world", 110.49, -4.5, existing,
                AngelSpawnBoundary.MINIMUM_WAVE_SPAWN_SEPARATION));
        assertTrue(AngelSpawnBoundary.separatedFromExisting("world", 110.5, -4.5, existing,
                AngelSpawnBoundary.MINIMUM_WAVE_SPAWN_SEPARATION));
        assertTrue(AngelSpawnBoundary.separatedFromExisting("world_nether", 10.5, -4.5, existing,
                AngelSpawnBoundary.MINIMUM_WAVE_SPAWN_SEPARATION));
    }
}
