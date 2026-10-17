package dev.prc.vendetta;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelicItemContractTest {
    @Test void acceptsOnlyExpectedTierMaterialAndVersionedIdentity() {
        assertTrue(RelicItemContract.valid(Material.ECHO_SHARD, 1, UUID.randomUUID().toString(), "imperfect", 1));
        assertTrue(RelicItemContract.valid(Material.DIAMOND, 2, UUID.randomUUID().toString(), "perfect", 1));
        assertTrue(RelicItemContract.valid(Material.TOTEM_OF_UNDYING, 3, UUID.randomUUID().toString(), "ascendant", 1));
    }

    @Test void rejectsAppearanceOnlyAndMalformedCounterfeits() {
        assertFalse(RelicItemContract.valid(Material.DIAMOND, 2, null, "perfect", 1));
        assertFalse(RelicItemContract.valid(Material.DIAMOND, 2, "not-a-uuid", "perfect", 1));
        assertFalse(RelicItemContract.valid(Material.DIAMOND, 2, UUID.randomUUID().toString(), "imperfect", 1));
        assertFalse(RelicItemContract.valid(Material.AMETHYST_SHARD, 2, UUID.randomUUID().toString(), "perfect", 1));
        assertFalse(RelicItemContract.valid(Material.DIAMOND, 2, UUID.randomUUID().toString(), "perfect", 2));
        assertFalse(RelicItemContract.valid(Material.TOTEM_OF_UNDYING, 1, UUID.randomUUID().toString(), "imperfect", 1));
    }
}
