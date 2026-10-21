package dev.prc.vendetta;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Small, server-independent rules for registering unique Shatter Sword IDs. */
final class ShatterSwordIds {
    private ShatterSwordIds() { }

    static boolean registerPaid(Set<String> paidIds, Set<String> usedIds, Collection<String> candidateIds) {
        Set<String> unique = new HashSet<>();
        for (String id : candidateIds) {
            if (id == null || id.isBlank() || !unique.add(id) || paidIds.contains(id) || usedIds.contains(id)) return false;
        }
        paidIds.addAll(unique);
        return true;
    }

    static boolean requiresAcquisitionCharge(String id, Set<String> paidIds) {
        return id == null || id.isBlank() || !paidIds.contains(id);
    }

    static boolean canActivate(String id, Set<String> paidIds, Set<String> usedIds) {
        return id != null && !id.isBlank() && paidIds.contains(id) && !usedIds.contains(id);
    }
}
