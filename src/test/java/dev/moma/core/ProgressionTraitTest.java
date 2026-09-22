package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressionTraitTest {
    private Arena arena(long gold,String...ids) {
        return new Arena("a",new UUID(0,1),new Grid(6),gold,100,new TraitLoadout(List.of(ids)),new HashRandom(7654));
    }
    private void buy(Arena arena,Rarity rarity) {
        assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,rarity),(t,r,c)->UUID.randomUUID()));
    }
    @Test void allOpeningPassivesStackByRarityAndEndIndependently() {
        Arena a=arena(100,"session_100","session_250","session_500","session_1000","round_125","round_250","round_350");
        assertEquals(0,a.traits().entries().size());assertEquals(5,a.traits().passives().size());
        assertEquals(Rarity.MYTHIC,a.summonRarity(Rarity.EPIC));
        int[] actual=new int[Rarity.values().length];
        for(int i=0;i<100000;i++)actual[a.rarityFromRoll(i).ordinal()]++;
        for(Rarity r:List.of(Rarity.NARRATIVE,Rarity.LEGENDARY,Rarity.EPIC,Rarity.MYTHIC))assertEquals(4000,actual[r.ordinal()]);
        assertEquals(5881,actual[Rarity.COMMON.ordinal()]);assertEquals(19,actual[Rarity.PRIMORDIAL.ordinal()]);
        buy(a,Rarity.LEGENDARY);
        assertFalse(a.openingTraitActive(Rarity.LEGENDARY));assertTrue(a.openingTraitActive(Rarity.EPIC));
        assertEquals(500,a.summonWeight(Rarity.LEGENDARY));assertEquals(4000,a.summonWeight(Rarity.MYTHIC));
        buy(a,Rarity.EPIC);assertEquals(Rarity.MYTHIC,a.lastSummoned().rarity());assertTrue(a.openingTraitActive(Rarity.MYTHIC));
        buy(a,Rarity.COMMON);assertFalse(a.openingTraitActive());assertEquals(Rarity.MYTHIC,a.summonRarity(Rarity.MYTHIC));
    }
    @Test void spendingCountsOnlySuccessfulChargesAndBuffIsCapped() {
        Arena a=arena(20000,"gold_spent_10000000");
        var roll=new SummonRoll(UnitType.WOLF,Rarity.COMMON);
        assertThrows(IllegalStateException.class,()->a.summon(a.owner(),roll,(t,r,c)->{throw new IllegalStateException();}));
        assertEquals(0,a.spentGold());assertEquals(1,a.damageMultiplier(AttackRole.MELEE_SINGLE,false));
        buy(a,Rarity.COMMON);assertEquals(10,a.spentGold());
        a.sellRarity(a.owner(),Rarity.COMMON);assertEquals(10,a.spentGold());
        a.reachedRound(101);
        for(int i=0;i<140;i++)buy(a,Rarity.LEGENDARY);
        assertEquals(14010,a.spentGold());assertEquals(1.12,a.damageMultiplier(AttackRole.MELEE_SINGLE,true),1e-9);
        Arena poor=arena(0,"gold_spent_1000");assertEquals(Arena.Result.INSUFFICIENT_COINS,poor.summon(poor.owner(),roll,(t,r,c)->UUID.randomUUID()));
        assertEquals(0,poor.spentGold());
    }
    @Test void duplicateBiasPreservesGradeStreamAndUsesOnlyOwnedSameGradeTypes() {
        Arena baseline=arena(100),boosted=arena(100,"duplicate_10000");
        buy(baseline,Rarity.PRIMORDIAL);buy(boosted,Rarity.PRIMORDIAL);
        HashRandom a=new HashRandom(3245),b=new HashRandom(3245);
        for(int i=0;i<10000;i++)assertEquals(SummonRoll.draw(a,baseline).rarity(),SummonRoll.draw(b,boosted).rarity());
        assertEquals(UnitType.PANDA,boosted.summonType(UnitType.PANDA,Rarity.MYTHIC));
        int wolves=0;
        for(int i=0;i<100000;i++)if(boosted.summonType(UnitType.PANDA,Rarity.PRIMORDIAL)==UnitType.WOLF)wolves++;
        assertEquals(15000,wolves,500);
        boosted.sellRarity(boosted.owner(),Rarity.PRIMORDIAL); // unsellable; the owned pool remains
        assertEquals(1,boosted.defenderCount());
    }
    @Test void duplicateBiasTargetsHighestEnhancementAndLeavesLowerGradesUniform() {
        var always=new java.util.random.RandomGenerator() {
            public long nextLong(){return 0;}
            public int nextInt(int bound){return 0;}
        };
        Arena a=new Arena("a",new UUID(0,1),new Grid(6),1000,100,new TraitLoadout(List.of("duplicate_10000")),always);
        buy(a,Rarity.COMMON);
        for(int i=0;i<100;i++)assertEquals(UnitType.PANDA,a.summonType(UnitType.PANDA,Rarity.COMMON));
        buy(a,Rarity.PRIMORDIAL);
        for(int i=0;i<3;i++)a.summon(a.owner(),new SummonRoll(UnitType.PANDA,Rarity.PRIMORDIAL),(t,r,c)->UUID.randomUUID());
        assertEquals(UnitType.PANDA,a.summonType(UnitType.WOLF,Rarity.PRIMORDIAL));
        assertEquals(UnitType.WOLF,a.summonType(UnitType.WOLF,Rarity.MYTHIC));
    }
}
