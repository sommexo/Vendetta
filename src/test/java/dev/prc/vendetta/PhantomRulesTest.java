package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhantomRulesTest {
    @Test void intoTheVoidInactivityPeriodIsFifteenSeconds() {
        assertEquals(15_000L, PhantomRules.INTO_THE_VOID_INACTIVITY_MILLIS);
    }

    @Test void intoTheVoidUsesTheConfiguredTierMultiplier() {
        assertEquals(1.10, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.DORMANT));
        assertEquals(1.15, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.IMPERFECT));
        assertEquals(1.30, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.PERFECT));
        assertEquals(1.50, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.ASCENDANT));
    }
}
