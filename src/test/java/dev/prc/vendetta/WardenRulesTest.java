package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WardenRulesTest {
    @Test void ancientGuardianRadiusAndEffectCycleMatchSpecification() {
        assertEquals(30, WardenRules.radius(VanguardProgression.Upgrade.DORMANT));
        assertEquals(30, WardenRules.radius(VanguardProgression.Upgrade.IMPERFECT));
        assertEquals(40, WardenRules.radius(VanguardProgression.Upgrade.PERFECT));
        assertEquals(50, WardenRules.radius(VanguardProgression.Upgrade.ASCENDANT));
        assertEquals(3, WardenRules.effectSeconds(0));
        assertEquals(2, WardenRules.effectSeconds(1));
        assertEquals(1, WardenRules.effectSeconds(2));
        assertEquals(3, WardenRules.effectSeconds(3));
    }

    @Test void cardsCapAtFiftyAndCaptureUsesThreeDimensionalDistance() {
        assertEquals(1.0, WardenRules.cardMultiplier(0));
        assertEquals(1.1, WardenRules.cardMultiplier(1), 0.000001);
        assertEquals(1.5, WardenRules.cardMultiplier(5), 0.000001);
        assertEquals(1.5, WardenRules.cardMultiplier(100));
        assertEquals("&bAG Cards: &f3", WardenRules.cardDisplayLine(3));
        assertEquals("&bAG Cards: &f0", WardenRules.cardDisplayLine(-1));
        assertTrue(WardenRules.withinCaptureEscapeRadius(10_000));
        assertFalse(WardenRules.withinCaptureEscapeRadius(10_001));
    }
}
