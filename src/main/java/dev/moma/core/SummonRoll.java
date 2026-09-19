package dev.moma.core;

import java.util.random.RandomGenerator;

public record SummonRoll(UnitType type, Rarity rarity) {
    public static SummonRoll draw(RandomGenerator random) {
        Rarity rarity = Rarity.fromRoll(random.nextInt(Rarity.TOTAL_WEIGHT));
        UnitType type = UnitType.values()[random.nextInt(UnitType.values().length)];
        return new SummonRoll(type, rarity);
    }
}
