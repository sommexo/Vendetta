package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShatterSwordIdsTest {
    @Test
    void cacheSwordIdsAreRegisteredExactlyAndDoNotRequireAnotherCharge() {
        Set<String> paid = new HashSet<>();
        Set<String> used = new HashSet<>();
        String guaranteedSword = UUID.randomUUID().toString();
        String randomRewardSword = UUID.randomUUID().toString();

        assertTrue(ShatterSwordIds.registerPaid(paid, used, List.of(guaranteedSword, randomRewardSword)));
        assertEquals(Set.of(guaranteedSword, randomRewardSword), paid);
        assertFalse(ShatterSwordIds.requiresAcquisitionCharge(guaranteedSword, paid));
        assertFalse(ShatterSwordIds.requiresAcquisitionCharge(randomRewardSword, paid));
        assertTrue(ShatterSwordIds.canActivate(guaranteedSword, paid, used));
    }

    @Test
    void duplicateOrPreviouslyUsedIdsAreRejectedWithoutPartialRegistration() {
        Set<String> paid = new HashSet<>(Set.of("existing"));
        Set<String> used = new HashSet<>(Set.of("used"));
        assertFalse(ShatterSwordIds.registerPaid(paid, used, List.of("new", "new")));
        assertFalse(ShatterSwordIds.registerPaid(paid, used, List.of("new", "used")));
        assertEquals(Set.of("existing"), paid);
        assertFalse(ShatterSwordIds.canActivate("used", paid, used));
    }

    @Test
    void paidAndUsedIdListsPreserveActivationStateAfterReload() {
        String unused = UUID.randomUUID().toString();
        String consumed = UUID.randomUUID().toString();
        Set<String> savedPaid = new HashSet<>(Set.of(unused, consumed));
        Set<String> savedUsed = new HashSet<>(Set.of(consumed));
        Set<String> loadedPaid = new HashSet<>(new ArrayList<>(savedPaid));
        Set<String> loadedUsed = new HashSet<>(new ArrayList<>(savedUsed));

        assertTrue(ShatterSwordIds.canActivate(unused, loadedPaid, loadedUsed));
        assertFalse(ShatterSwordIds.canActivate(consumed, loadedPaid, loadedUsed));
        assertTrue(ShatterSwordIds.requiresAcquisitionCharge("unregistered", loadedPaid));
    }
}
