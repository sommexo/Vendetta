package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AngelWaveSpawnResultTest {
    @Test
    void manualRequestCanBeAcceptedWithoutFalselyClaimingWaveStarted() {
        AngelWaveSpawnResult result = new AngelWaveSpawnResult(true, false, 8, List.of());
        assertTrue(result.requestAccepted());
        assertFalse(result.started());
        assertEquals(0, result.spawned());
        assertTrue(result.coordinateLines().isEmpty());
    }

    @Test
    void successfulSpawnReportUsesRecordedActualCoordinatesAndEntityIdentity() {
        UUID entity = UUID.randomUUID();
        AngelSpawnBoundary.SpawnPosition actual = new AngelSpawnBoundary.SpawnPosition("world_nether", 13.5, 71.0, -8.25);
        AngelWaveSpawnResult result = new AngelWaveSpawnResult(true, true, 8,
                List.of(new AngelWaveSpawnResult.SpawnedAngel(entity, "wanderer", actual)));
        assertTrue(result.started());
        assertEquals(1, result.spawned());
        assertEquals(entity, result.angels().get(0).entityId());
        assertEquals(actual, result.angels().get(0).position());
        assertEquals(List.of("&ewanderer Angel &fworld_nether X 13.5 Y 71.0 Z -8.25"), result.coordinateLines());
    }

    @Test
    void inconsistentOutcomeCannotClaimStartedWithoutSpawnedAngels() {
        assertThrows(IllegalArgumentException.class, () -> new AngelWaveSpawnResult(true, true, 8, List.of()));
    }
}
