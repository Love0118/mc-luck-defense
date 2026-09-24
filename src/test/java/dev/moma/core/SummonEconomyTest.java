package dev.moma.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummonEconomyTest {
    private double mean(SummonTier tier) {
        long total=0;
        for(int roll=0;roll<Rarity.TOTAL_WEIGHT;roll++)total+=tier.saleValue(tier.rarity(roll,false));
        return (double)total/Rarity.TOTAL_WEIGHT;
    }
    @Test void exhaustiveSaleRecoveryMatchesRelicFloorAndSharedPrices() {
        assertEquals(5.19701,mean(SummonTier.NORMAL),1e-9);
        assertEquals(mean(SummonTier.NORMAL)/10,mean(SummonTier.ADVANCED)/100,0.002);
        assertEquals(51.8324,mean(SummonTier.ADVANCED),1e-9);
        assertEquals(997.786,mean(SummonTier.ASCENDED),1e-9);
        assertEquals(2494.092,mean(SummonTier.MIRACLE),1e-9);
        for(SummonTier tier:SummonTier.values())assertTrue(mean(tier)<tier.cost());
    }
    @Test void bothTiersUseSamePricesAndSalesCannotPayTwice() {
        for(SummonTier tier:SummonTier.values())for(Rarity rarity:Rarity.values()) {
            UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),tier.cost(),100);
            arena.reachedRound(tier.unlockRound());
            assertEquals(Arena.Result.OK,arena.summon(owner,new SummonRoll(UnitType.WOLF,rarity),(t,r,c)->UUID.randomUUID()));
            assertEquals(0,arena.coins());Defender defender=arena.lastSummoned();
            assertEquals(tier.saleValue(rarity),defender.saleValue());
            arena.select(owner,defender.entityId());
            assertEquals(rarity.salePrice().isPresent()?Arena.Result.OK:Arena.Result.NOT_SELLABLE,arena.sellSelected(owner));
            assertEquals(tier.saleValue(rarity),arena.coins());
            arena.sellSelected(owner);assertEquals(tier.saleValue(rarity),arena.coins());
        }
    }
    @Test void mergingAcrossTierChangeKeepsSharedMaterialValueForBulkSale() {
        UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),110,100);
        var roll=new SummonRoll(UnitType.WOLF,Rarity.RELIC);
        arena.summon(owner,roll,(t,r,c)->UUID.randomUUID());arena.reachedRound(101);
        arena.summon(owner,roll,(t,r,c)->UUID.randomUUID());
        assertEquals(1,arena.defenderCount());assertEquals(48,arena.lastSummoned().saleValue());
        assertEquals(48,arena.sellRarity(owner,Rarity.RELIC).income());assertEquals(48,arena.coins());
        assertEquals(0,arena.sellRarity(owner,Rarity.RELIC).income());
    }
    @Test void newPricesDoNotRevaluePreviouslyBoughtFusionMaterials() {
        UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),7100,100);
        var roll=new SummonRoll(UnitType.WOLF,Rarity.EPIC);
        for(int round:new int[]{499,500,1000}) {
            arena.reachedRound(round);
            assertEquals(Arena.Result.OK,arena.summon(owner,roll,(t,r,c)->UUID.randomUUID()));
        }
        assertEquals(0,arena.coins());assertEquals(2,arena.lastSummoned().enhancement());
        assertEquals(3100,arena.lastSummoned().saleValue());
        assertEquals(3100,arena.sellRarity(owner,Rarity.EPIC).income());
    }
}
