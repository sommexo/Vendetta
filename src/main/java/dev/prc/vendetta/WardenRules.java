package dev.prc.vendetta;

import java.util.Locale;

final class WardenRules {
    private WardenRules() { }
    static double radius(VanguardProgression.Upgrade tier) {
        return switch (tier) {
            case DORMANT, IMPERFECT -> 30;
            case PERFECT -> 40;
            case ASCENDANT -> 50;
        };
    }
    static int effectSeconds(long sequence) { return new int[]{3, 2, 1}[Math.floorMod(sequence, 3)]; }
    static double cardMultiplier(int cards) { return 1.0 + Math.max(0, Math.min(GuardianCardLedger.MAX_CARDS, cards)) * 0.01; }
    static String cardDisplayLine(int cards) { return "&bAG Cards: &f" + Math.max(0, Math.min(GuardianCardLedger.MAX_CARDS, cards)); }
    static String cardEntryLine(String name, int cards, boolean mob) { return cardEntryLine(name, cards, mob, 0); }
    static String cardEntryLine(String name, int cards, boolean mob, int uniqueIndex) {
        int count = Math.max(0, Math.min(GuardianCardLedger.MAX_CARDS, cards));
        String sanitized = name == null || name.isBlank() ? "Unknown" : name.replace('&', ' ').replace('\n', ' ').replace("\r", "");
        String suffix = (mob ? " [MOB]" : "") + " " + count + " (" + String.format(Locale.ROOT, "%.2f", cardMultiplier(count)) + "x)";
        String unique = "&" + "0123456789abcdef".charAt(Math.floorMod(uniqueIndex, 16));
        int maxName = Math.max(1, 40 - 2 - suffix.length() - unique.length());
        if (sanitized.length() > maxName) sanitized = sanitized.substring(0, maxName);
        return "&f" + sanitized + suffix + unique;
    }
    static boolean withinCaptureEscapeRadius(double distanceSquared) { return distanceSquared <= 10_000.0; }
}
