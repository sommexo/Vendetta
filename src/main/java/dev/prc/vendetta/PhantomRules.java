package dev.prc.vendetta;

final class PhantomRules {
    static final long INTO_THE_VOID_INACTIVITY_MILLIS = 15_000L;
    static final long PREDATORS_INSTINCT_ARM_WINDOW_MILLIS = 1_000L;
    static final long PREDATORS_INSTINCT_COOLDOWN_MILLIS = 45_000L;
    static final long PREDATORS_INSTINCT_STUN_MILLIS = 5_000L;

    static long intoTheVoidDurationMillis(VanguardProgression.Upgrade tier) {
        return switch (tier) {
            case DORMANT -> 5_000L;
            case IMPERFECT -> 15_000L;
            case PERFECT, ASCENDANT -> 25_000L;
        };
    }

    private PhantomRules() { }

    static double intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade tier) {
        return switch (tier) {
            case DORMANT -> 1.0;
            case IMPERFECT -> 1.10;
            case PERFECT -> 1.25;
            case ASCENDANT -> 1.50;
        };
    }
}
