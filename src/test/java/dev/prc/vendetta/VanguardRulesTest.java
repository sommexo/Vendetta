package dev.prc.vendetta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanguardRulesTest {
    @Test void berserkerUsesAbsoluteHeartThresholdsAndReplacementMultipliers() {
        assertEquals(1.0, VanguardRules.berserkerMultiplier(VanguardProgression.Upgrade.DORMANT, 20.0));
        assertEquals(1.10, VanguardRules.berserkerMultiplier(VanguardProgression.Upgrade.DORMANT, 19.99));
        assertEquals(1.25, VanguardRules.berserkerMultiplier(VanguardProgression.Upgrade.IMPERFECT, 9.0));
        assertEquals(1.50, VanguardRules.berserkerMultiplier(VanguardProgression.Upgrade.PERFECT, 10.0));
        assertEquals(1.75, VanguardRules.berserkerMultiplier(VanguardProgression.Upgrade.PERFECT, 9.99));
    }

    @Test void bloodthirstWindowIncreasesWithEveryTier() {
        assertEquals(750L, VanguardRules.bloodthirstWindowMillis(VanguardProgression.Upgrade.DORMANT));
        assertEquals(1_000L, VanguardRules.bloodthirstWindowMillis(VanguardProgression.Upgrade.IMPERFECT));
        assertEquals(1_250L, VanguardRules.bloodthirstWindowMillis(VanguardProgression.Upgrade.PERFECT));
        assertEquals(1_500L, VanguardRules.bloodthirstWindowMillis(VanguardProgression.Upgrade.ASCENDANT));
    }

    @Test void onlyActiveImperfectUltimateBlocksFood() {
        assertTrue(VanguardRules.blocksUltimateFood(true, true, VanguardProgression.Upgrade.IMPERFECT));
        assertFalse(VanguardRules.blocksUltimateFood(true, false, VanguardProgression.Upgrade.IMPERFECT));
        assertFalse(VanguardRules.blocksUltimateFood(true, true, VanguardProgression.Upgrade.DORMANT));
        assertFalse(VanguardRules.blocksUltimateFood(true, true, VanguardProgression.Upgrade.PERFECT));
        assertFalse(VanguardRules.blocksUltimateFood(true, true, VanguardProgression.Upgrade.ASCENDANT));
        assertFalse(VanguardRules.blocksUltimateFood(false, true, VanguardProgression.Upgrade.IMPERFECT));
    }
}
