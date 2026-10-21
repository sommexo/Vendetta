package dev.prc.vendetta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Deterministic view of the authoritative Marks balances for sidebar display. */
final class MarksLeaderboard {
    record Entry(UUID id, String name, long balance) { }

    private static final int LIMIT = 5;
    private static final int MAX_LINE_LENGTH = 40;

    private MarksLeaderboard() { }

    static List<Entry> topFive(Collection<Entry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingLong(Entry::balance).reversed()
                        .thenComparing(entry -> safeName(entry.name()).toLowerCase(Locale.ROOT))
                        .thenComparing(entry -> safeName(entry.name()))
                        .thenComparing(entry -> entry.id() == null ? "" : entry.id().toString()))
                .limit(LIMIT)
                .toList();
    }

    static List<String> lines(Collection<Entry> entries) {
        List<String> lines = new ArrayList<>();
        lines.add("&8--- MARKS TOP ---");
        List<Entry> top = topFive(entries);
        for (int index = 0; index < top.size(); index++) {
            Entry entry = top.get(index);
            String rank = "&7" + (index + 1) + ". ";
            String balance = Long.toString(entry.balance());
            int nameLength = Math.max(1, MAX_LINE_LENGTH - rank.length() - 1 - balance.length());
            String name = safeName(entry.name());
            if (name.length() > nameLength) name = name.substring(0, nameLength);
            String value = rank + name + " " + balance;
            lines.add(value);
        }
        return List.copyOf(lines);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "Unknown";
        return name.replace('&', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
