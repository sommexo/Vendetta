package dev.prc.vendetta;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassInventoryRulesTest {
    @Test void maceRestrictionIsVanguardOnly() {
        assertTrue(ClassInventoryRules.prohibited("vanguard", Material.MACE));
        assertFalse(ClassInventoryRules.prohibited("phantom", Material.MACE));
        assertFalse(ClassInventoryRules.prohibited("warden", Material.MACE));
        assertFalse(ClassInventoryRules.prohibited("vanguard", Material.NETHERITE_SWORD));
    }
}
