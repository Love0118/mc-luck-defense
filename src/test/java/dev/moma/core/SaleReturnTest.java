package dev.moma.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaleReturnTest {
    private double expected(SummonTier tier) {
        long total=0;
        for(int roll=0;roll<Rarity.TOTAL_WEIGHT;roll++)total+=tier.saleValue(tier.rarity(roll,false));
        return (double)total/Rarity.TOTAL_WEIGHT;
    }
    @Test void exhaustiveDrawReturnsMatchPerGoldWithoutChangingUpperGradePrices() {
        assertEquals(6.36003,expected(SummonTier.NORMAL),1e-9);
        assertEquals(63.5527,expected(SummonTier.ADVANCED),1e-9);
        assertEquals(expected(SummonTier.NORMAL)/10,expected(SummonTier.ADVANCED)/100,0.001);
        long[] prices={32,65,97,173,323,60,150,375,0,0};
        for(Rarity rarity:Rarity.values())assertEquals(prices[rarity.ordinal()],SummonTier.ADVANCED.saleValue(rarity));
    }
    private void summon(Arena arena,UnitType type,Rarity rarity) {
        assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(type,rarity),(t,r,c)->UUID.randomUUID()));
    }
    @Test void tierTransitionDoesNotRepriceOldUnitsAndMixedMergesPayExactlyOnce() {
        UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),1000,100);
        summon(arena,UnitType.WOLF,Rarity.COMMON);Defender old=arena.lastSummoned();
        arena.reachedRound(101);assertEquals(3,old.saleValue());
        summon(arena,UnitType.WOLF,Rarity.COMMON);assertSame(old,arena.lastSummoned());assertEquals(35,old.saleValue());
        summon(arena,UnitType.RABBIT,Rarity.COMMON);assertEquals(32,arena.lastSummoned().saleValue());
        assertEquals(790,arena.coins());
        var sale=arena.sellRarity(owner,Rarity.COMMON);assertEquals(67,sale.income());assertEquals(857,arena.coins());
        assertEquals(0,arena.sellRarity(owner,Rarity.COMMON).income());assertEquals(857,arena.coins());
    }
    @Test void promotionAndAbsorptionPreserveBothPurchaseValues() {
        UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),5000,100);
        summon(arena,UnitType.WOLF,Rarity.RARE);Defender rare=arena.lastSummoned();
        summon(arena,UnitType.WOLF,Rarity.COMMON);arena.reachedRound(101);
        for(int i=0;i<20;i++)summon(arena,UnitType.WOLF,Rarity.COMMON);
        Defender merged=arena.lastSummoned();assertEquals(Rarity.RARE,merged.rarity());
        assertEquals(649,merged.saleValue());assertEquals(1,arena.defenderCount());
        assertEquals(java.util.List.of(rare.entityId()),arena.collectMergedEntities());
        arena.select(owner,merged.entityId());assertEquals(Arena.Result.OK,arena.sellSelected(owner));
        assertEquals(3629,arena.coins());assertEquals(Arena.Result.NO_SELECTION,arena.sellSelected(owner));
    }
    @Test void advancedSalesUseTierValuesForEveryRarityAndPrimordialsRemainUnsellable() {
        for(Rarity rarity:Rarity.values()) {
            UUID owner=UUID.randomUUID();var arena=new Arena("a",owner,new Grid(6),100,100);arena.reachedRound(101);
            summon(arena,UnitType.WOLF,rarity);arena.select(owner,arena.lastSummoned().entityId());
            assertEquals(rarity.salePrice().isPresent()?Arena.Result.OK:Arena.Result.NOT_SELLABLE,arena.sellSelected(owner));
            assertEquals(SummonTier.ADVANCED.saleValue(rarity),arena.coins());
        }
    }
}
