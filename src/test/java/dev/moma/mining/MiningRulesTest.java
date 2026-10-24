package dev.moma.mining;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningRulesTest {
    @Test void actualIronSpeedUsesPercentagePointsAndRespectsBounds() {
        assertEquals(3f,MiningRules.speed(50));assertEquals(12f,MiningRules.speed(200));assertEquals(6f,MiningRules.speed(100));
        assertThrows(IllegalArgumentException.class,()->MiningRules.speed(49));assertThrows(IllegalArgumentException.class,()->MiningRules.speed(201));
    }
    @Test void rangesIncreaseCostAndRiskButProtectLowUpgrades() {
        long prior=0;int priorRisk=0;
        for(int level=50;level<200;level+=25){var offer=MiningRules.offer(MiningRules.Upgrade.SPEED,level);assertTrue(offer.cost()>prior);assertTrue(offer.destruction()>=priorRisk);assertTrue(offer.success()+offer.destruction()<=100);prior=offer.cost();priorRisk=offer.destruction();}
        assertEquals(0,MiningRules.offer(MiningRules.Upgrade.SPEED,99).destruction());assertTrue(MiningRules.offer(MiningRules.Upgrade.SPEED,100).destruction()>0);
        assertEquals(0,MiningRules.offer(MiningRules.Upgrade.FORTUNE,0).destruction());assertThrows(IllegalArgumentException.class,()->MiningRules.offer(MiningRules.Upgrade.SPEED,200));
        assertThrows(IllegalArgumentException.class,()->MiningRules.offer(MiningRules.Upgrade.FORTUNE,3));
    }
    @Test void successesGainOneToTenPointsAndFailuresNeverChangeLevels() {
        var random=new Random(182);var gains=new HashSet<Integer>();var outcomes=EnumSet.noneOf(MiningRules.Outcome.class);
        for(int i=0;i<10000;i++){var attempt=MiningRules.roll(MiningRules.Upgrade.SPEED,150,random);outcomes.add(attempt.outcome());if(attempt.outcome()==MiningRules.Outcome.SUCCESS){assertTrue(attempt.gain()>=1&&attempt.gain()<=10);gains.add(attempt.gain());}else assertEquals(0,attempt.gain());}
        assertEquals(10,gains.size());assertEquals(3,outcomes.size());
        for(int i=0;i<1000;i++){var low=MiningRules.roll(MiningRules.Upgrade.SPEED,50,random);assertNotEquals(MiningRules.Outcome.DESTROYED,low.outcome());var cap=MiningRules.roll(MiningRules.Upgrade.SPEED,199,random);assertTrue(cap.gain()<=1);}
    }
    @Test void strictQuarterThresholdCountsOnlyRemainingOre() {
        assertFalse(MiningRules.depleted(25,100,.25));assertTrue(MiningRules.depleted(24,100,.25));
        assertFalse(MiningRules.depleted(0,0,.25));assertFalse(MiningRules.depleted(100,100,.25));
    }
    @Test void everyUnitPriceIsAnIntegerAtMostFiveAndSaleRejectsOverflow() {
        assertEquals(0,MiningRules.price(-1));assertEquals(5,MiningRules.price(999));
        assertEquals(0,MiningRules.sale(Map.of(),Map.of()));
        assertEquals(14,MiningRules.sale(Map.of(MiningRules.Ore.DIAMOND,3L,MiningRules.Ore.COPPER,2L),Map.of()));
        assertEquals(15,MiningRules.sale(Map.of(MiningRules.Ore.DIAMOND,3L),Map.of(MiningRules.Ore.DIAMOND,999L)));
        assertThrows(ArithmeticException.class,()->MiningRules.sale(Map.of(MiningRules.Ore.EMERALD,Long.MAX_VALUE),Map.of()));
        assertThrows(IllegalArgumentException.class,()->MiningRules.sale(Map.of(MiningRules.Ore.IRON,-1L),Map.of()));
    }
    @Test void manualDropsFollowVanillaFortuneBoundsAndImproveExpectedYield() {
        for(var ore:MiningRules.Ore.values()) {
            long zero=0,three=0;var random=new Random(913);
            for(int i=0;i<10_000;i++) {
                int normal=MiningRules.drops(ore,0,random),upgraded=MiningRules.drops(ore,3,random);
                int min=switch(ore){case COPPER->2;case LAPIS,REDSTONE->4;default->1;};
                int max=switch(ore){case COPPER->20;case LAPIS->36;case REDSTONE->8;default->4;};
                assertTrue(normal>=min);assertTrue(upgraded>=min && upgraded<=max);zero+=normal;three+=upgraded;
            }
            assertTrue(three>zero,"Fortune must affect "+ore);
        }
        assertThrows(IllegalArgumentException.class,()->MiningRules.drops(MiningRules.Ore.COAL,4,new Random()));
    }
}
