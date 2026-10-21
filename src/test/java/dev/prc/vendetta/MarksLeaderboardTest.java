package dev.prc.vendetta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarksLeaderboardTest {
    @Test
    void ranksHighestBalancesFirstAndUsesDeterministicNameTieBreaks() {
        UUID steveId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID alexId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<MarksLeaderboard.Entry> top = MarksLeaderboard.topFive(List.of(
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Zero", 0),
                new MarksLeaderboard.Entry(steveId, "Steve", 100),
                new MarksLeaderboard.Entry(alexId, "Alex", 100),
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Dream", 150),
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Player4", 80),
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Player5", 60),
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Player6", 40)));

        assertEquals(5, top.size());
        assertEquals("Dream", top.get(0).name());
        assertEquals("Alex", top.get(1).name());
        assertEquals("Steve", top.get(2).name());
        assertEquals("Player4", top.get(3).name());
        assertEquals("Player5", top.get(4).name());
    }

    @Test
    void formatsRanksZeroBalancesAndDistinctRankLines() {
        List<String> lines = MarksLeaderboard.lines(List.of(
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Alex", 0),
                new MarksLeaderboard.Entry(UUID.randomUUID(), "Steve", 0)));
        assertEquals("&8--- MARKS TOP ---", lines.get(0));
        assertEquals("&71. Alex 0", lines.get(1));
        assertEquals("&72. Steve 0", lines.get(2));
        assertNotEquals(lines.get(1), lines.get(2));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 40));
    }

    @Test
    void activeLayoutKeepsPersonalBalanceAndLeaderboardWithinSidebarLimit() {
        GuardianCardLedger cards = new GuardianCardLedger();
        for (int i = 0; i < 50; i++) cards.addHit(UUID.randomUUID(), "Enemy" + i, false);
        List<MarksLeaderboard.Entry> entries = List.of(new MarksLeaderboard.Entry(UUID.randomUUID(), "Steve", 100));
        int pages = AncientGuardianSidebar.pageCount("Warden", cards.entries());
        List<String> lines = AncientGuardianSidebar.lines("Warden", cards.entries(), pages - 1, 100,
                MarksLeaderboard.lines(entries));
        assertTrue(lines.size() <= AncientGuardianSidebar.MAX_LINES);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Marks: &f100")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("MARKS TOP")));
    }
}
