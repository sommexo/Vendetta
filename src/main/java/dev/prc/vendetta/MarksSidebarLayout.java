package dev.prc.vendetta;

import java.util.ArrayList;
import java.util.List;

final class MarksSidebarLayout {
    private MarksSidebarLayout() { }

    static List<String> inactive(long marks, List<String> leaderboardLines) {
        List<String> lines = new ArrayList<>();
        lines.add("&eMarks: &f" + marks);
        lines.addAll(leaderboardLines);
        return List.copyOf(lines);
    }
}
