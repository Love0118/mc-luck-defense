package dev.moma.core;

/** Advanced odds balance sale recovery with shared prices and capped Narrative/Legendary yield. */
public enum SummonTier {
    NORMAL(10), ADVANCED(100);
    private final long cost;
    SummonTier(long cost) { this.cost=cost; }
    public long cost() { return cost; }
    public int weight(Rarity rarity, boolean openingBonus) {
        if(this==NORMAL)return rarity.weight(openingBonus);
        return switch(rarity) {
            case COMMON -> 5000;
            case RARE -> 10000;
            case ANCIENT -> 20000;
            case RELIC -> 41810;
            case NARRATIVE, LEGENDARY -> 5000;
            case EPIC -> 9000;
            case MYTHIC -> 4000;
            case PRIMORDIAL -> 190;
            case TRUE_PRIMORDIAL -> 0;
        };
    }
    public Rarity rarity(int roll, boolean openingBonus) {
        if(roll<0 || roll>=Rarity.TOTAL_WEIGHT)throw new IllegalArgumentException("Roll outside distribution");
        int boundary=0;
        for(Rarity rarity:Rarity.values())if(roll<(boundary+=weight(rarity,openingBonus)))return rarity;
        throw new IllegalStateException("Invalid weights");
    }
}
