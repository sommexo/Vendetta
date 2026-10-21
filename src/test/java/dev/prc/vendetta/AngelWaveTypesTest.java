package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class AngelWaveTypesTest {
    @Test
    void normalWaveContainsEveryTypeAndFillsEightSlots() {
        var types = AngelWaveTypes.compose(Set.of(), 8, ThreadLocalRandom.current());

        assertEquals(8, types.size());
        assertTrue(types.containsAll(AngelWaveTypes.ALL));
    }

    @Test
    void excludedTypeIsNotSpawnedForTheAffectedWave() {
        var types = AngelWaveTypes.compose(Set.of("wanderer"), 8, ThreadLocalRandom.current());

        assertEquals(8, types.size());
        assertFalse(types.contains("wanderer"));
        assertTrue(types.containsAll(Set.of("crazy", "imperfect", "perfect", "ascendant")));
    }

    @Test
    void allExcludedProducesNoIllegalFallbackType() {
        var types = AngelWaveTypes.compose(Set.copyOf(AngelWaveTypes.ALL), 8, ThreadLocalRandom.current());

        assertTrue(types.isEmpty());
    }
}
