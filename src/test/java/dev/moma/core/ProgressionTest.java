package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    @Test void fullHundredRoundCompositionRepeatsWithScaledHealthAndRewards() {
        var rules=CampaignRules.standard();
        for(int round=1;round<=100;round++) {
            var a=WaveSchedule.create(round,rules);var b=WaveSchedule.create(round+100,rules);
            assertEquals(a.name(),b.name());assertEquals(a.entries().size(),b.entries().size());
            for(int i=0;i<a.entries().size();i++) {
                var x=a.entries().get(i);var y=b.entries().get(i);
                assertEquals(x.offsetTick(),y.offsetTick());assertEquals(x.enemy().type(),y.enemy().type());
                assertEquals(x.enemy().boss(),y.enemy().boss());assertEquals(x.enemy().speed(),y.enemy().speed());
                assertTrue(y.enemy().health()>x.enemy().health());assertTrue(y.enemy().reward()>=x.enemy().reward());
            }
        }
    }
    @Test void advancedWeightsStartAtRelicAndPreserveTopThreeYieldPerGold() {
        int[] advanced={0,0,0,32010,35000,30000,2000,800,190,0};
        for(SummonTier tier:SummonTier.values()) {
            int[] counts=new int[Rarity.values().length];
            for(int i=0;i<Rarity.TOTAL_WEIGHT;i++)counts[tier.rarity(i,false).ordinal()]++;
            assertEquals(100000,Arrays.stream(counts).sum());
            for(Rarity rarity:Rarity.values())assertEquals(tier.weight(rarity,false),counts[rarity.ordinal()]);
            assertEquals(0,counts[Rarity.TRUE_PRIMORDIAL.ordinal()]);
            if(tier==SummonTier.ADVANCED)assertArrayEquals(advanced,counts);
        }
        for(Rarity rarity:List.of(Rarity.EPIC,Rarity.MYTHIC,Rarity.PRIMORDIAL))
            assertEquals(rarity.weight()*10,SummonTier.ADVANCED.weight(rarity,false));
        assertEquals(Rarity.PRIMORDIAL.weight()*10,SummonTier.ADVANCED.weight(Rarity.PRIMORDIAL,false));
        for(Rarity rarity:Rarity.values())assertEquals(SummonTier.ADVANCED.weight(rarity,false),SummonTier.ADVANCED.weight(rarity,true));
        assertThrows(IllegalArgumentException.class,()->SummonTier.ADVANCED.rarity(-1,false));
        assertThrows(IllegalArgumentException.class,()->SummonTier.ADVANCED.rarity(100000,false));
    }
    @Test void round101ChangesPriceOnceAndFailureDoesNotCharge() {
        UUID owner=UUID.randomUUID();Arena arena=new Arena("a",owner,new Grid(6),100,100);
        arena.reachedRound(100);assertEquals(10,arena.summonCost());
        arena.reachedRound(101);arena.reachedRound(1);assertEquals(100,arena.summonCost());assertFalse(arena.openingBonusActive());
        assertEquals(Arena.Result.OK,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.MYTHIC),(t,r,c)->UUID.randomUUID()));
        assertEquals(0,arena.coins());
        assertEquals(Arena.Result.INSUFFICIENT_COINS,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.MYTHIC),(t,r,c)->{fail();return null;}));
        assertEquals(0,arena.coins());
    }
    @Test void promotionsKeepCooldownAndDamageAndDoNotMintSaleGold() {
        for(Rarity rarity:Rarity.values()) {
            Defender d=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",UnitType.WOLF,rarity,new Cell(0,0));
            d.attackAt(100,40);UUID target=UUID.randomUUID();d.hitTarget(target);
            double previous=d.profile().damage();
            for(int i=0;i<20;i++){d.merge();assertTrue(d.profile().damage()>=previous);previous=d.profile().damage();}
            assertEquals(rarity==Rarity.TRUE_PRIMORDIAL?rarity:Rarity.values()[rarity.ordinal()+1],d.rarity());
            assertEquals(rarity==Rarity.TRUE_PRIMORDIAL?20:0,d.enhancement());
            assertEquals(140,d.nextAttackTick());assertEquals(1,d.consecutiveHits());
            assertEquals(d.rarity().salePrice().isEmpty()?0:rarity.salePrice().orElse(0)*21L,d.saleValue());
        }
    }
    @Test void truePrimordialMatchesOldPlusThirtyAndStaysOutOfDrawPool() {
        for(UnitType type:UnitType.values()) {
            var primordial=type.profile().at(Rarity.PRIMORDIAL);var highest=type.profile().at(Rarity.TRUE_PRIMORDIAL);
            assertEquals(primordial.damage()*34,highest.damage());assertEquals(primordial.intervalTicks(),highest.intervalTicks());
            assertEquals(primordial.range(),highest.range());assertEquals(4,Rarity.TRUE_PRIMORDIAL.abilityLevel());
        }
    }
    @Test void promotionCombinesExistingHigherGradeAndReturnsRemovedEntity() {
        UUID owner=UUID.randomUUID();Arena arena=new Arena("a",owner,new Grid(6),1000,100);
        var common=new SummonRoll(UnitType.WOLF,Rarity.COMMON);var rare=new SummonRoll(UnitType.WOLF,Rarity.RARE);
        arena.summon(owner,rare,(t,r,c)->UUID.randomUUID());Defender existing=arena.lastSummoned();existing.attackAt(10,100);
        arena.select(owner,existing.entityId());
        for(int i=0;i<21;i++)arena.summon(owner,common,(t,r,c)->UUID.randomUUID());
        Defender result=arena.lastSummoned();assertEquals(Rarity.RARE,result.rarity());assertEquals(1,result.enhancement());
        assertEquals(1,arena.defenderCount());assertSame(result,arena.selected().orElseThrow());assertEquals(110,result.nextAttackTick());
        assertEquals(List.of(existing.entityId()),arena.collectMergedEntities());assertTrue(arena.collectMergedEntities().isEmpty());
        assertEquals(24,result.saleValue());
    }
}
