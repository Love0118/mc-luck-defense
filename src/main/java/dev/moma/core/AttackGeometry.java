package dev.moma.core;

/** Shared horizontal damage geometry for combat and its on-screen footprint. */
public final class AttackGeometry {
    public static final double CLEAVE_HALF_ANGLE_COSINE = 0.5;
    public static final double CLEAVE_HALF_ANGLE_RADIANS = Math.acos(CLEAVE_HALF_ANGLE_COSINE);
    private AttackGeometry() {}

    public static double areaRadius(Defender defender, CombatProfile profile) {
        return profile.areaRadius() + (defender.type().role() == AttackRole.LARGE_AREA ? 0.5 * defender.rarity().abilityLevel() : 0);
    }
    public static boolean inCleave(Point origin, Point facing, Point target, double range) {
        if (origin.distanceSquared(target) > range * range) return false;
        double ax = facing.x() - origin.x(), az = facing.z() - origin.z();
        double bx = target.x() - origin.x(), bz = target.z() - origin.z();
        double lengths = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        // With a coincident primary the existing combat rule covers the full radius.
        return lengths == 0 || (ax * bx + az * bz) / lengths >= CLEAVE_HALF_ANGLE_COSINE;
    }
}
