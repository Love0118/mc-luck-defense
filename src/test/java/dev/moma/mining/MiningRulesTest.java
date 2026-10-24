package dev.moma.mining;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningRulesTest {
    @Test void actualIronSpeedStartsAtHalfAndStopsAtDouble() {
        assertEquals(3f,MiningRules.speed(0));assertEquals(12f,MiningRules.speed(6));
        for(int i=1;i<=6;i++)assertEquals(1.5f,MiningRules.speed(i)-MiningRules.speed(i-1));
        assertThrows(IllegalArgumentException.class,()->MiningRules.speed(-1));
        assertThrows(IllegalArgumentException.class,()->MiningRules.speed(7));
    }
    @Test void speedAndFortuneCostsAndCapsAreIndependent() {
        assertEquals(30,MiningRules.upgradeCost(MiningRules.Upgrade.SPEED,0,30));
        assertEquals(180,MiningRules.upgradeCost(MiningRules.Upgrade.SPEED,5,30));
        assertEquals(180,MiningRules.upgradeCost(MiningRules.Upgrade.FORTUNE,2,60));
        assertThrows(IllegalArgumentException.class,()->MiningRules.upgradeCost(MiningRules.Upgrade.SPEED,6,30));
        assertThrows(IllegalArgumentException.class,()->MiningRules.upgradeCost(MiningRules.Upgrade.FORTUNE,3,60));
        assertEquals(1,MiningRules.upgradeCost(MiningRules.Upgrade.SPEED,0,-99));
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
