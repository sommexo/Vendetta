package dev.prc.vendetta;

final class RelicProgressionRules {
    private RelicProgressionRules() { }

    static VanguardProgression.Upgrade next(VanguardProgression.Upgrade current, int relicTier, boolean ultimate) {
        if (current == null) return null;
        if (relicTier == 1 && !ultimate && current == VanguardProgression.Upgrade.DORMANT)
            return VanguardProgression.Upgrade.IMPERFECT;
        if (relicTier == 2 && !ultimate && current == VanguardProgression.Upgrade.IMPERFECT)
            return VanguardProgression.Upgrade.PERFECT;
        if (relicTier == 2 && ultimate && current != VanguardProgression.Upgrade.PERFECT
                && current != VanguardProgression.Upgrade.ASCENDANT) return VanguardProgression.Upgrade.PERFECT;
        if (relicTier == 3 && ultimate && current == VanguardProgression.Upgrade.PERFECT)
            return VanguardProgression.Upgrade.ASCENDANT;
        return null;
    }
}
