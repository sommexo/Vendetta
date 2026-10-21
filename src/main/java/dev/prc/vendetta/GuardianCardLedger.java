package dev.prc.vendetta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One shared Ancient Guardian stack per enemy entity, kept only for its active ultimate. */
final class GuardianCardLedger {
    static final int MAX_CARDS = 50;

    record Card(UUID entityId, String name, boolean mob, int count) { }

    private final Map<UUID, Card> byEnemy = new LinkedHashMap<>();

    Card addHit(UUID enemyId, String name, boolean mob) {
        if (enemyId == null) throw new IllegalArgumentException("Enemy UUID is required");
        Card previous = byEnemy.get(enemyId);
        String safeName = name == null || name.isBlank() ? previous == null ? "Unknown" : previous.name() : name;
        int count = Math.min(MAX_CARDS, previous == null ? 1 : previous.count() + 1);
        Card updated = new Card(enemyId, safeName, mob, count);
        byEnemy.put(enemyId, updated);
        return updated;
    }

    int count(UUID enemyId) {
        Card card = byEnemy.get(enemyId);
        return card == null ? 0 : card.count();
    }

    Card get(UUID enemyId) { return byEnemy.get(enemyId); }

    List<Card> entries() { return List.copyOf(new ArrayList<>(byEnemy.values())); }

    boolean remove(UUID enemyId) { return byEnemy.remove(enemyId) != null; }

    void clear() { byEnemy.clear(); }

    boolean isEmpty() { return byEnemy.isEmpty(); }
}
