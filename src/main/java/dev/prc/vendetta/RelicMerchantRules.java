package dev.prc.vendetta;

import java.util.List;

/** Villager-level offer progression for designated relic merchants. */
final class RelicMerchantRules {
    record Offer(String relicClass, int relicTier) { }

    private RelicMerchantRules() { }

    static int relicTier(int villagerLevel) {
        if (villagerLevel >= 5) return 3;
        if (villagerLevel >= 3) return 2;
        return 1;
    }

    static List<Offer> offers(int villagerLevel) {
        int tier = relicTier(villagerLevel);
        return List.of(new Offer("vanguard", tier), new Offer("warden", tier), new Offer("phantom", tier));
    }
}
