package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhantomRulesTest {
    @Test void intoTheVoidInactivityPeriodIsFifteenSeconds() {
        assertEquals(15_000L, PhantomRules.INTO_THE_VOID_INACTIVITY_MILLIS);
    }

    @Test void predatorsInstinctTimingMatchesCounterSpecification() {
        assertEquals(1_000L, PhantomRules.PREDATORS_INSTINCT_ARM_WINDOW_MILLIS);
        assertEquals(45_000L, PhantomRules.PREDATORS_INSTINCT_COOLDOWN_MILLIS);
        assertEquals(5_000L, PhantomRules.PREDATORS_INSTINCT_STUN_MILLIS);
    }

    @Test void intoTheVoidDurationScalesByTier() {
        assertEquals(5_000L, PhantomRules.intoTheVoidDurationMillis(VanguardProgression.Upgrade.DORMANT));
        assertEquals(15_000L, PhantomRules.intoTheVoidDurationMillis(VanguardProgression.Upgrade.IMPERFECT));
        assertEquals(25_000L, PhantomRules.intoTheVoidDurationMillis(VanguardProgression.Upgrade.PERFECT));
        assertEquals(25_000L, PhantomRules.intoTheVoidDurationMillis(VanguardProgression.Upgrade.ASCENDANT));
    }

    @Test void intoTheVoidUsesTheConfiguredTierMultiplier() {
        assertEquals(1.0, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.DORMANT));
        assertEquals(1.10, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.IMPERFECT));
        assertEquals(1.25, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.PERFECT));
        assertEquals(1.50, PhantomRules.intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade.ASCENDANT));
    }
}
