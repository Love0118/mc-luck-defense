package dev.moma.core;

public enum SummonTier {
    NORMAL(10,1), ADVANCED(100,101), ASCENDED(2000,500), MIRACLE(5000,1000);
    private final long cost;
    private final int round;
    SummonTier(long cost,int round) { this.cost=cost;this.round=round; }
    public long cost() { return cost; }
    public int unlockRound() { return round; }
    public static SummonTier atRound(int round) {
        SummonTier tier=NORMAL;
        for(SummonTier candidate:values())if(round>=candidate.round)tier=candidate;
        return tier;
    }
    public long saleValue(Rarity rarity) {
        if(this==ASCENDED)return switch(rarity) {
            case LEGENDARY -> 700;
            case EPIC -> 1000;
            case MYTHIC -> 1500;
            case PRIMORDIAL -> 2000;
            default -> rarity.salePrice().orElse(0);
        };
        if(this==MIRACLE)return switch(rarity) {
            case EPIC -> 1800;
            case MYTHIC -> 3000;
            case PRIMORDIAL -> 4000;
            default -> rarity.salePrice().orElse(0);
        };
        return rarity.salePrice().orElse(0);
    }
    public int weight(Rarity rarity,boolean openingBonus) {
        if(this==NORMAL)return rarity.weight(openingBonus);
        if(this==ASCENDED)return switch(rarity) {
            case LEGENDARY -> 40198;
            case EPIC -> 40000;
            case MYTHIC -> 16000;
            case PRIMORDIAL -> 3800;
            case TRUE_PRIMORDIAL -> 2;
            default -> 0;
        };
        if(this==MIRACLE)return switch(rarity) {
            case EPIC -> 50494;
            case MYTHIC -> 40000;
            case PRIMORDIAL -> 9500;
            case TRUE_PRIMORDIAL -> 5;
            case MIRACLE -> 1;
            default -> 0;
        };
        return switch(rarity) {
            case RELIC -> 32010;
            case NARRATIVE -> 35000;
            case LEGENDARY -> 30000;
            case EPIC -> 2000;
            case MYTHIC -> 800;
            case PRIMORDIAL -> 190;
            default -> 0;
        };
    }
    public Rarity rarity(int roll,boolean openingBonus) {
        if(roll<0 || roll>=Rarity.TOTAL_WEIGHT)throw new IllegalArgumentException("Roll outside distribution");
        int boundary=0;
        for(Rarity rarity:Rarity.values())if(roll<(boundary+=weight(rarity,openingBonus)))return rarity;
        throw new IllegalStateException("Invalid weights");
    }
}
