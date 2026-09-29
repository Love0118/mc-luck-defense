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
        assertEquals(5.69201,mean(SummonTier.NORMAL),1e-9);
        assertEquals(mean(SummonTier.NORMAL)/10,mean(SummonTier.ADVANCED)/100,0.002);
        assertEquals(56.7824,mean(SummonTier.ADVANCED),1e-9);
        assertEquals(424.9184,mean(SummonTier.ASCENDED),1e-9);
        assertEquals(818.113,mean(SummonTier.MIRACLE),1e-9);
        for(SummonTier tier:SummonTier.values())assertTrue(mean(tier)<tier.cost());
    }
    @Test void bothTiersUseSamePricesAndSalesCannotPayTwice() {
        for(SummonTier tier:SummonTier.values())for(Rarity rarity:Rarity.values()) {
            assertEquals(rarity.salePrice().orElse(0),tier.saleValue(rarity));
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
        UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),4800,100);
        var roll=new SummonRoll(UnitType.WOLF,Rarity.EPIC);
        for(int round:new int[]{499,500,1000}) {
            arena.reachedRound(round);
            assertEquals(Arena.Result.OK,arena.summon(owner,roll,(t,r,c)->UUID.randomUUID()));
        }
        assertEquals(0,arena.coins());assertEquals(2,arena.lastSummoned().enhancement());
        assertEquals(1200,arena.lastSummoned().saleValue());
        assertEquals(1200,arena.sellRarity(owner,Rarity.EPIC).income());
    }

    @Test void thresholdsAndStateRestoreNeverRepriceExistingMaterials()throws Exception {
        var owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),10000,100);
        var roll=new SummonRoll(UnitType.WOLF,Rarity.EPIC);
        arena.summon(owner,roll,(t,r,c)->UUID.randomUUID());
        var stored=Defender.class.getDeclaredField("saleValue");stored.setAccessible(true);
        stored.setLong(arena.lastSummoned(),300);
        for(int round:new int[]{500,1000}) {
            arena.reachedRound(round);
            assertEquals(round==500?300:700,arena.defenders().getFirst().saleValue());
            arena=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(arena),Arena.class);
            arena.summon(owner,roll,(t,r,c)->UUID.randomUUID());
        }
        assertEquals(1100,arena.lastSummoned().saleValue());
        assertEquals(1100,arena.sellRarity(owner,Rarity.EPIC).income());
        assertEquals(0,arena.sellRarity(owner,Rarity.EPIC).income());
    }

    @Test void rarestGradesKeepRefundAdjustedExpectedAcquisitionCost() {
        assertEquals((2000-998.365)/.00005+20000,
                (SummonTier.ASCENDED.cost()-mean(SummonTier.ASCENDED))/.00005+30000,550_000);
        assertEquals((5000-2503.730)/.00012+20000,
                (SummonTier.MIRACLE.cost()-mean(SummonTier.MIRACLE))/.00012+30000,120_000);
        assertEquals((5000-2503.730)/.00003+420000,
                (SummonTier.MIRACLE.cost()-mean(SummonTier.MIRACLE))/.00003+630000,280_000);
        for(Rarity grade:java.util.List.of(Rarity.MYTHIC,Rarity.PRIMORDIAL)) {
            double target=SummonTier.ADVANCED.weight(grade,false)/(SummonTier.ADVANCED.cost()-mean(SummonTier.ADVANCED));
            for(SummonTier tier:java.util.List.of(SummonTier.ASCENDED,SummonTier.MIRACLE))
                assertEquals(target,tier.weight(grade,false)/(tier.cost()-mean(tier)),target*.001);
        }
    }
}
