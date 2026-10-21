package dev.prc.vendetta;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AncientGuardianSidebarTest {
    @Test
    void rendersCasterAndCorrectPlayerAndMobLines() {
        GuardianCardLedger cards = new GuardianCardLedger();
        cards.addHit(UUID.randomUUID(), "Steve", false);
        for (int i = 0; i < 18; i++) cards.addHit(UUID.nameUUIDFromBytes("alex".getBytes()), "Alex", false);
        for (int i = 0; i < 19; i++) cards.addHit(UUID.nameUUIDFromBytes("zombie".getBytes()), "Zombie", true);

        List<String> sidebar = AncientGuardianSidebar.lines("WardenCaster", cards.entries(), 0, 321);
        String rendered = plain(String.join("\n", sidebar));
        assertTrue(plain(String.join(" ", sidebar)).contains("Ancient Guardian activated by WardenCaster:"));
        assertTrue(rendered.contains("Steve 1 (1.01x)"));
        assertTrue(rendered.contains("Alex 18 (1.18x)"));
        assertTrue(rendered.contains("Zombie [MOB] 19 (1.19x)"));
        assertFalse(rendered.contains("Steve [MOB]"));
    }

    @Test
    void paginatesWithoutDroppingCasterOrCardsAndWrapsLongCasterName() {
        GuardianCardLedger cards = new GuardianCardLedger();
        for (int i = 0; i < 40; i++) cards.addHit(UUID.randomUUID(), "Enemy" + i, i % 2 == 0);
        String caster = "SixteenCharacter";
        int pages = AncientGuardianSidebar.pageCount(caster, cards.entries());
        assertTrue(pages > 1);
        List<String> allPages = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            List<String> lines = AncientGuardianSidebar.lines(caster, cards.entries(), page, 0);
            assertTrue(lines.size() <= AncientGuardianSidebar.MAX_LINES);
            assertTrue(lines.stream().allMatch(line -> line.length() <= 40));
            assertTrue(lines.stream().anyMatch(line -> line.contains("activated by " + caster + ":")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("&bCards:")));
            allPages.addAll(lines);
        }
        for (int i = 0; i < 40; i++) {
            int enemyIndex = i;
            assertTrue(allPages.stream().anyMatch(line -> plain(line).contains("Enemy" + enemyIndex + " ")));
        }
    }

    @Test
    void formatsMultipliersAtRequiredStackBoundaries() {
        assertTrue(plain(WardenRules.cardEntryLine("Zero", 0, false)).contains("Zero 0 (1.00x)"));
        assertTrue(plain(WardenRules.cardEntryLine("One", 1, false)).contains("One 1 (1.01x)"));
        assertTrue(plain(WardenRules.cardEntryLine("Five", 5, false)).contains("Five 5 (1.05x)"));
        assertTrue(plain(WardenRules.cardEntryLine("Eighteen", 18, false)).contains("Eighteen 18 (1.18x)"));
        assertTrue(plain(WardenRules.cardEntryLine("Fifty", 50, false)).contains("Fifty 50 (1.50x)"));
        assertTrue(plain(WardenRules.cardEntryLine("Zombie", 19, true)).contains("Zombie [MOB] 19 (1.19x)"));
    }

    @Test
    void visibilityRequiresTheActiveWardenCasterAndEndsWhenInactive() {
        UUID caster = UUID.randomUUID();
        UUID otherWarden = UUID.randomUUID();
        assertFalse(AncientGuardianSidebar.visible(false, "warden", caster, caster));
        assertFalse(AncientGuardianSidebar.visible(true, "vanguard", caster, caster));
        assertFalse(AncientGuardianSidebar.visible(true, "phantom", caster, caster));
        assertFalse(AncientGuardianSidebar.visible(true, "warden", otherWarden, caster));
        assertTrue(AncientGuardianSidebar.visible(true, "warden", caster, caster));
        assertFalse(AncientGuardianSidebar.visible(false, "warden", caster, caster));
    }

    @Test
    void inactiveSidebarKeepsPersonalMarksAndHasNoGuardianContent() {
        List<String> lines = MarksSidebarLayout.inactive(725, MarksLeaderboard.lines(List.of()));
        String rendered = String.join("\n", lines);
        assertTrue(rendered.contains("&eMarks: &f725"));
        assertTrue(rendered.contains("--- MARKS TOP ---"));
        assertFalse(rendered.contains("Ancient Guardian"));
        assertFalse(rendered.contains("AG Cards"));
    }

    private String plain(String value) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
    }
}
