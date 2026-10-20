package dev.prc.vendetta;

final class VanguardRules {
    private VanguardRules() { }

    static double berserkerMultiplier(VanguardProgression.Upgrade tier, double health) {
        if (health >= 20.0) return 1.0;
        if (tier == VanguardProgression.Upgrade.PERFECT && health < 10.0) return 1.75;
        return switch (tier) {
            case DORMANT -> 1.10;
            case IMPERFECT -> 1.25;
            case PERFECT -> 1.50;
            case ASCENDANT -> 1.0;
        };
    }

    static long bloodthirstWindowMillis(VanguardProgression.Upgrade tier) {
        return tier == VanguardProgression.Upgrade.IMPERFECT ? 1_000L : 750L;
    }

    static boolean blocksUltimateFood(boolean vanguard, boolean ultimateActive,
                                      VanguardProgression.Upgrade ultimateTier) {
        return vanguard && ultimateActive && ultimateTier == VanguardProgression.Upgrade.IMPERFECT;
    }
}
