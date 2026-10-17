package dev.prc.vendetta;

import org.bukkit.inventory.EquipmentSlot;

final class ActivationRules {
    private ActivationRules() { }
    static boolean primaryHand(EquipmentSlot hand) { return hand == EquipmentSlot.HAND; }
}
