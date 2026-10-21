package dev.prc.vendetta;

import java.util.Map;
import java.util.UUID;

/** Geometry and tracking helpers for Angel spawns. Bukkit world borders are axis-aligned squares. */
final class AngelSpawnBoundary {
    private static final double DISTANCE_RATIO = 1.5;
    static final double MINIMUM_WAVE_SPAWN_SEPARATION = 100.0;

    private AngelSpawnBoundary() { }

    /**
     * Checks that a point is inside the border and no farther from the world spawn than
     * 1/1.5 of the distance to the border along that point's ray from world spawn.
     */
    static boolean contains(double borderCenterX, double borderCenterZ, double borderSize,
                            double spawnX, double spawnZ, double candidateX, double candidateZ) {
        if (!Double.isFinite(borderCenterX) || !Double.isFinite(borderCenterZ)
                || !Double.isFinite(borderSize) || borderSize <= 0
                || !Double.isFinite(spawnX) || !Double.isFinite(spawnZ)
                || !Double.isFinite(candidateX) || !Double.isFinite(candidateZ)) return false;

        double half = borderSize / 2.0;
        double minX = borderCenterX - half;
        double maxX = borderCenterX + half;
        double minZ = borderCenterZ - half;
        double maxZ = borderCenterZ + half;
        if (!strictlyInside(spawnX, minX, maxX) || !strictlyInside(spawnZ, minZ, maxZ)
                || !strictlyInside(candidateX, minX, maxX) || !strictlyInside(candidateZ, minZ, maxZ)) return false;

        double dx = candidateX - spawnX;
        double dz = candidateZ - spawnZ;
        double distance = Math.hypot(dx, dz);
        if (distance == 0) return true;

        double directionX = dx / distance;
        double directionZ = dz / distance;
        double edgeDistanceX = directionX > 0 ? (maxX - spawnX) / directionX
                : directionX < 0 ? (minX - spawnX) / directionX : Double.POSITIVE_INFINITY;
        double edgeDistanceZ = directionZ > 0 ? (maxZ - spawnZ) / directionZ
                : directionZ < 0 ? (minZ - spawnZ) / directionZ : Double.POSITIVE_INFINITY;
        double borderDistance = Math.min(edgeDistanceX, edgeDistanceZ);
        double permittedDistance = borderDistance / DISTANCE_RATIO;
        return Double.isFinite(permittedDistance) && distance <= permittedDistance + 1.0e-9;
    }

    private static boolean strictlyInside(double value, double min, double max) {
        return value > min && value < max;
    }

    record SpawnPosition(String world, double x, double y, double z) { }

    static boolean separatedFromExisting(String world, double x, double z,
                                          Iterable<SpawnPosition> existing, double minimumDistance) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(minimumDistance) || minimumDistance < 0) return false;
        double minimumSquared = minimumDistance * minimumDistance;
        for (SpawnPosition position : existing) {
            if (!world.equals(position.world())) continue;
            double dx = x - position.x();
            double dz = z - position.z();
            if (dx * dx + dz * dz < minimumSquared) return false;
        }
        return true;
    }

    static void recordSuccessfulSpawn(Map<UUID, SpawnPosition> positions, UUID entityId,
                                      String world, double x, double y, double z) {
        if (positions == null || entityId == null || world == null || world.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Angel spawn tracking requires a UUID and finite actual coordinates");
        }
        positions.put(entityId, new SpawnPosition(world, x, y, z));
    }
}
