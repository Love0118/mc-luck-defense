package dev.moma.core;

import java.util.random.RandomGenerator;

public record SummonRoll(UnitType type, Rarity rarity) {
    public static SummonRoll draw(RandomGenerator random, Arena arena) {
        if(!arena.openingTraitActive()) {
            SummonRoll base=arena.summonTier()==SummonTier.NORMAL
                    ? draw(random,arena.openingBonusActive()):draw(random,false,arena.summonTier());
            UnitType type=arena.summonType(base.type(),base.rarity());
            return type==base.type()?base:new SummonRoll(type,base.rarity());
        }
        Rarity rarity=arena.rarityFromRoll(random.nextInt(Rarity.TOTAL_WEIGHT));
        UnitType type=UnitType.values()[random.nextInt(UnitType.values().length)];
        return new SummonRoll(arena.summonType(type,rarity),rarity);
    }
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
