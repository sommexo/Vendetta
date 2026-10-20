package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuardianCardLedgerTest {
    @Test
    void everyHitAddsOneAndAlliedAttackersShareTheEnemyStack() {
        GuardianCardLedger cards = new GuardianCardLedger();
        UUID alex = UUID.randomUUID();
        cards.addHit(alex, "Alex", false); // caster
        assertEquals(1, cards.count(alex));
        cards.addHit(alex, "Alex", false); // ally Dream
        cards.addHit(alex, "Alex", false); // ally Sommexo_
        assertEquals(3, cards.count(alex));
        assertEquals(1, cards.entries().size());
    }

    @Test
    void enemiesHaveIndependentStacksAndBothPlayerAndMobTargetsAreTracked() {
        GuardianCardLedger cards = new GuardianCardLedger();
        UUID alex = UUID.randomUUID();
        UUID zombie = UUID.randomUUID();
        cards.addHit(alex, "Alex", false);
        cards.addHit(zombie, "Zombie", true);
        cards.addHit(zombie, "Zombie", true);
        assertEquals(1, cards.count(alex));
        assertEquals(2, cards.count(zombie));
        assertEquals("Alex", cards.entries().get(0).name());
        assertFalse(cards.entries().get(0).mob());
        assertEquals("Zombie", cards.entries().get(1).name());
        assertTrue(cards.entries().get(1).mob());
    }

    @Test
    void eachEnemyStackCapsAtFifty() {
        GuardianCardLedger cards = new GuardianCardLedger();
        UUID enemy = UUID.randomUUID();
        for (int i = 0; i < 90; i++) cards.addHit(enemy, "Alex", false);
        assertEquals(50, cards.count(enemy));
    }

    @Test
    void emptyNamesFallBackAndResetRemovesSidebarEntries() {
        GuardianCardLedger cards = new GuardianCardLedger();
        UUID enemy = UUID.randomUUID();
        cards.addHit(enemy, "Alex", false);
        cards.addHit(enemy, "", false);
        assertEquals("Alex", cards.entries().get(0).name());
        assertTrue(AncientGuardianSidebar.lines("Steve", cards.entries(), 0, 12).stream()
                .anyMatch(line -> line.contains("Alex 2 (1.2x)")));
        assertTrue(cards.remove(enemy));
        assertTrue(AncientGuardianSidebar.lines("Steve", cards.entries(), 0, 12).stream()
                .noneMatch(line -> line.contains("Alex")));
        cards.addHit(enemy, "Alex", false);
        cards.clear();
        assertTrue(cards.isEmpty());
    }
}
