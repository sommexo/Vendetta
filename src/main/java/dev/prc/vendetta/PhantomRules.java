package dev.prc.vendetta;

final class PhantomRules {
    private PhantomRules() { }

    static double intoTheVoidNextHitMultiplier(VanguardProgression.Upgrade tier) {
        return switch (tier) {
            case DORMANT -> 1.10;
            case IMPERFECT -> 1.15;
            case PERFECT -> 1.30;
            case ASCENDANT -> 1.50;
        };
    }
}
