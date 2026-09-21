package dev.moma.core;

/** Campaign combat values. Distances are blocks; time is ticks. */
public record CombatProfile(double damage, int intervalTicks, double range, double areaRadius, int targets) implements java.io.Serializable {
    public CombatProfile at(Rarity rarity) {
        return new CombatProfile(damage * rarity.damageMultiplier(),
                Math.max(2, (int) Math.ceil(intervalTicks / rarity.speedMultiplier())),
                range * rarity.rangeMultiplier(), areaRadius, targets);
    }
}
