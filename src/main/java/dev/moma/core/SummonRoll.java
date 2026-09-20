package dev.moma.core;

import java.util.random.RandomGenerator;

public record SummonRoll(UnitType type, Rarity rarity) {
    public static SummonRoll draw(RandomGenerator random) {
        return draw(random, false);
    }
    public static SummonRoll draw(RandomGenerator random, boolean openingBonus) {
        return draw(random, openingBonus, SummonTier.NORMAL);
    }
    public static SummonRoll draw(RandomGenerator random, boolean openingBonus, SummonTier tier) {
        Rarity rarity = tier.rarity(random.nextInt(Rarity.TOTAL_WEIGHT), openingBonus);
        UnitType type = UnitType.values()[random.nextInt(UnitType.values().length)];
        return new SummonRoll(type, rarity);
    }
}
