package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelicMerchantRulesTest {
    @Test void eachVillagerLevelOffersOneRelicForEachClassAtItsTier() {
        List<String> expectedClasses = List.of("vanguard", "warden", "phantom");
        for (int level = 1; level <= 5; level++) {
            int expectedTier = level <= 2 ? 1 : level <= 4 ? 2 : 3;
            List<RelicMerchantRules.Offer> offers = RelicMerchantRules.offers(level);
            assertEquals(3, offers.size());
            assertEquals(expectedClasses, offers.stream().map(RelicMerchantRules.Offer::relicClass).toList());
            assertEquals(List.of(expectedTier, expectedTier, expectedTier),
                    offers.stream().map(RelicMerchantRules.Offer::relicTier).toList());
        }
    }

    @Test void masterAlwaysOffersAllThreeAscendantRelics() {
        assertEquals(List.of(
                new RelicMerchantRules.Offer("vanguard", 3),
                new RelicMerchantRules.Offer("warden", 3),
                new RelicMerchantRules.Offer("phantom", 3)), RelicMerchantRules.offers(5));
    }
}
