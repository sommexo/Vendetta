package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelicProgressionRulesTest {
    @Test void imperfectRelicUpgradesOneRegularAbilityToImperfectOnly() {
        assertEquals(VanguardProgression.Upgrade.IMPERFECT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 1, false));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.IMPERFECT, 1, false));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 1, true));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 3, false));
    }

    @Test void perfectRelicUpgradesAnImperfectRegularAbility() {
        assertEquals(VanguardProgression.Upgrade.PERFECT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.IMPERFECT, 2, false));
        assertEquals(VanguardProgression.Upgrade.PERFECT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 2, false));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.PERFECT, 3, false));
    }

    @Test void perfectAndAscendantFollowRegularAndUltimateProgression() {
        assertEquals(VanguardProgression.Upgrade.PERFECT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.IMPERFECT, 2, false));
        assertEquals(VanguardProgression.Upgrade.PERFECT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 2, true));
        assertEquals(VanguardProgression.Upgrade.ASCENDANT,
                RelicProgressionRules.next(VanguardProgression.Upgrade.PERFECT, 3, true));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.DORMANT, 3, true));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.PERFECT, 2, true));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.PERFECT, 3, false));
        assertNull(RelicProgressionRules.next(VanguardProgression.Upgrade.IMPERFECT, 3, false));
    }
}
