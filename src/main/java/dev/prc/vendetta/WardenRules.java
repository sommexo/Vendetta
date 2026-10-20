package dev.prc.vendetta;

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
    static double cardMultiplier(int cards) { return Math.min(1.50, 1.0 + Math.max(0, Math.min(50, cards)) * 0.1); }
    static String cardDisplayLine(int cards) { return "&bAG Cards: &f" + Math.max(0, Math.min(50, cards)); }
    static boolean withinCaptureEscapeRadius(double distanceSquared) { return distanceSquared <= 10_000.0; }
}
