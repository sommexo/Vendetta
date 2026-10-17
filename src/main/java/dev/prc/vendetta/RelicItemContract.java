package dev.prc.vendetta;

import org.bukkit.Material;

import java.util.UUID;

/** Pure relic identity contract shared by factory and PDC validation. */
final class RelicItemContract {
    static final int FORMAT_VERSION = 1;

    private RelicItemContract() { }

    static Material material(int tier) {
        return switch (tier) {
            case 1 -> Material.ECHO_SHARD;
            case 2 -> Material.DIAMOND;
            case 3 -> Material.TOTEM_OF_UNDYING;
            default -> Material.AIR;
        };
    }

    static String tierId(int tier) {
        return switch (tier) {
            case 1 -> "imperfect";
            case 2 -> "perfect";
            case 3 -> "ascendant";
            default -> "invalid";
        };
    }

    static boolean valid(Material itemMaterial, int tier, String relicId, String tierId, int format) {
        if (tier < 1 || tier > 3) return false;
        if (itemMaterial != material(tier) || format != FORMAT_VERSION || !RelicItemContract.tierId(tier).equals(tierId)) return false;
        try { UUID.fromString(relicId); return true; }
        catch (IllegalArgumentException | NullPointerException ex) { return false; }
    }
}
