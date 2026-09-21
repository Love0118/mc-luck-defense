package dev.moma.core;

/** Advanced draws start at Relic; Epic, Mythic and Primordial retain tenfold normal odds. */
public enum SummonTier {
    NORMAL(10), ADVANCED(100);
    private final long cost;
    SummonTier(long cost) { this.cost=cost; }
    public long cost() { return cost; }
    public int weight(Rarity rarity, boolean openingBonus) {
        if(this==NORMAL)return rarity.weight(openingBonus);
        return switch(rarity) {
            case COMMON, RARE, ANCIENT -> 0;
            case RELIC -> 32010;
            case NARRATIVE -> 35000;
            case LEGENDARY -> 30000;
            case EPIC -> 2000;
            case MYTHIC -> 800;
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
