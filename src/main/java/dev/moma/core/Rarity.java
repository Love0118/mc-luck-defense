package dev.moma.core;

import java.util.OptionalInt;

public enum Rarity {
    COMMON("일반", 50001, 1, 1.75, 1.0, 1.0, 0),
    RARE("레어", 33100, 3, 2.5, 1.0, 1.0, 0),
    ANCIENT("고대", 10200, 5, 3.5, 1.0, 1.0, 0),
    RELIC("유물", 5100, 24, 5.0, 1.1, 1.15, 0),
    NARRATIVE("서사", 800, 35, 7.0, 1.15, 1.25, 0),
    LEGENDARY("전설", 500, 60, 8.0, 1.2, 1.4, 1),
    EPIC("에픽", 200, 300, 24.0, 1.25, 1.6, 2),
    MYTHIC("신화", 80, 750, 120.0, 1.3, 1.9, 3),
    PRIMORDIAL("태초", 19, 1000, 4800.0, 1.4, 2.3, 4),
    TRUE_PRIMORDIAL("진 태초", 0, 20000, 163200.0, 1.4, 2.3, 4),
    MIRACLE("미라클", 0, 420000, 5548800.0, 1.4, 2.3, 5);

    public static final int TOTAL_WEIGHT = 100_000;
    private final String label;
    private final int weight, salePrice, abilityLevel;
    private final double damageMultiplier, rangeMultiplier, speedMultiplier;

    Rarity(String label, int weight, int salePrice, double damage, double range, double speed, int abilityLevel) {
        this.label = label; this.weight = weight; this.salePrice = salePrice;
        this.damageMultiplier = damage; this.rangeMultiplier = range;
        this.speedMultiplier = speed; this.abilityLevel = abilityLevel;
    }
    public String label() { return label; }
    public int weight() { return weight; }
    public int weight(boolean openingBonus) {
        if (!openingBonus) return weight;
        return switch (this) {
            case COMMON -> 20301;
            case ANCIENT -> 30000;
            case RELIC -> 15000;
            default -> weight;
        };
    }
    public OptionalInt salePrice() { return salePrice < 0 ? OptionalInt.empty() : OptionalInt.of(salePrice); }
    public boolean autoSellable() { return ordinal()<=PRIMORDIAL.ordinal(); }
    public int abilityLevel() { return abilityLevel; }
    public double damageMultiplier() { return damageMultiplier; }
    public double rangeMultiplier() { return rangeMultiplier; }
    public double speedMultiplier() { return speedMultiplier; }

    public static Rarity fromRoll(int roll) {
        return fromRoll(roll, false);
    }
    public static Rarity fromRoll(int roll, boolean openingBonus) {
        if (roll < 0 || roll >= TOTAL_WEIGHT) throw new IllegalArgumentException("roll outside [0,100000)");
        int boundary = 0;
        for (Rarity rarity : values()) {
            boundary += rarity.weight(openingBonus);
            if (roll < boundary) return rarity;
        }
        throw new IllegalStateException("Rarity weights do not sum to 100000");
    }
}
