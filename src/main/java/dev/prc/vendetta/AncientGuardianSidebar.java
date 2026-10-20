package dev.prc.vendetta;

import java.util.ArrayList;
import java.util.List;

/** Keeps Ancient Guardian details within the vanilla sidebar's 15-line and 40-character bounds. */
final class AncientGuardianSidebar {
    static final int MAX_LINES = 15;
    private static final int MAX_SCORE_TEXT_LENGTH = 40;

    private AncientGuardianSidebar() { }

    static int pageCount(String caster, List<GuardianCardLedger.Card> cards) {
        int normalCapacity = entryCapacity(caster, false);
        if (cards.size() <= normalCapacity) return 1;
        int pagedCapacity = entryCapacity(caster, true);
        return (cards.size() + pagedCapacity - 1) / pagedCapacity;
    }

    static List<String> lines(String caster, List<GuardianCardLedger.Card> cards, int requestedPage, long marks) {
        List<String> lines = header(caster);
        lines.add("&bCards:");
        if (cards.isEmpty()) {
            lines.add("&7No enemies have cards");
        } else {
            int pages = pageCount(caster, cards);
            int page = Math.floorMod(requestedPage, pages);
            int capacity = entryCapacity(caster, pages > 1);
            int start = page * capacity;
            int end = Math.min(cards.size(), start + capacity);
            for (int index = start; index < end; index++) {
                lines.add(WardenRules.cardEntryLine(cards.get(index).name(), cards.get(index).count(),
                        cards.get(index).mob(), index - start));
            }
            if (pages > 1) lines.add("&8Page " + (page + 1) + "/" + pages);
        }
        lines.add("&eMarks: &f" + marks);
        return List.copyOf(lines);
    }

    private static List<String> header(String caster) {
        String name = safeName(caster, 32);
        String full = "&6Ancient Guardian activated by " + name + ":";
        if (full.length() <= MAX_SCORE_TEXT_LENGTH) return new ArrayList<>(List.of(full));
        return new ArrayList<>(List.of("&6Ancient Guardian", "&6activated by " + safeName(caster, 27) + ":"));
    }

    private static int entryCapacity(String caster, boolean hasPageFooter) {
        int headerLines = header(caster).size();
        // Header, Cards label, Marks line, and optionally the page footer use reserved rows.
        return MAX_LINES - headerLines - 2 - (hasPageFooter ? 1 : 0);
    }

    private static String safeName(String name, int maxLength) {
        if (name == null || name.isBlank()) return "Unknown";
        String sanitized = name.replace('&', ' ').replace('\n', ' ').replace("\r", "");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }
}
