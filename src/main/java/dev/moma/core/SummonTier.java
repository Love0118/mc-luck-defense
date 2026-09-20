package dev.moma.core;

import java.util.*;

/** Integer weights preserve the exact upper-grade yield per gold at round 101. */
public enum SummonTier {
    NORMAL(10), ADVANCED(100);
    private final long cost;
    private static final int[] ADVANCED_WEIGHTS = advancedWeights();
    SummonTier(long cost) { this.cost=cost; }
    public long cost() { return cost; }
    public int weight(Rarity rarity, boolean openingBonus) {
        return this==NORMAL ? rarity.weight(openingBonus) : ADVANCED_WEIGHTS[rarity.ordinal()];
    }
    public Rarity rarity(int roll, boolean openingBonus) {
        if(roll<0 || roll>=Rarity.TOTAL_WEIGHT)throw new IllegalArgumentException("Roll outside distribution");
        int boundary=0;
        for(Rarity rarity:Rarity.values())if(roll<(boundary+=weight(rarity,openingBonus)))return rarity;
        throw new IllegalStateException("Invalid weights");
    }
    private static int[] advancedWeights() {
        int[] result=new int[Rarity.values().length];
        int lower=0,remaining=Rarity.TOTAL_WEIGHT;
        for(Rarity rarity:Rarity.values()) {
            if(rarity.ordinal()>=Rarity.LEGENDARY.ordinal()) {result[rarity.ordinal()]=rarity.weight()*10;remaining-=result[rarity.ordinal()];}
            else lower+=rarity.weight();
        }
        int assigned=0;
        List<Rarity> byRemainder=new ArrayList<>();
        final int budget=remaining,denominator=lower;
        for(Rarity rarity:Rarity.values())if(rarity.ordinal()<Rarity.LEGENDARY.ordinal()) {
            result[rarity.ordinal()]=(int)((long)rarity.weight()*budget/denominator);assigned+=result[rarity.ordinal()];byRemainder.add(rarity);
        }
        byRemainder.sort(Comparator.<Rarity>comparingLong(r->(long)r.weight()*budget%denominator).reversed());
        for(int i=0;i<budget-assigned;i++)result[byRemainder.get(i).ordinal()]++;
        return result;
    }
}
