package dev.moma.core;

/** Development balance values, not final wave balance. Distances are blocks; time is ticks. */
public record CombatProfile(double damage, int intervalTicks, double range, double areaRadius, int targets) {
    public CombatProfile at(Rarity rarity) {
        return new CombatProfile(damage * rarity.damageMultiplier(),
                Math.max(2, (int) Math.ceil(intervalTicks / rarity.speedMultiplier())),
                range * rarity.rangeMultiplier(), areaRadius, targets);
    }
}
