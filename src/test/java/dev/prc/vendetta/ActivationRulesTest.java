package dev.prc.vendetta;

import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActivationRulesTest {
    @Test void offHandEventsCannotDoubleActivate() {
        assertTrue(ActivationRules.primaryHand(EquipmentSlot.HAND));
        assertFalse(ActivationRules.primaryHand(EquipmentSlot.OFF_HAND));
    }
}
