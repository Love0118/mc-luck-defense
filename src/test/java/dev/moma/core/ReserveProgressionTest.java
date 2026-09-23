package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReserveProgressionTest {
    private Arena arena(){return new Arena("a",UUID.randomUUID(),new Grid(6),100000,100);}
    private void buy(Arena a,UnitType type,Rarity grade) {
        assertEquals(Arena.Result.OK,a.summon(a.owner(),new SummonRoll(type,grade),(t,r,c)->UUID.randomUUID()));
    }
    @Test void fullFieldAcceptsExactlyFortyEightReserveUnitsAndNeverAttacksFromReserve() {
        Arena a=arena();a.toggleMerging(a.owner());
        for(int i=0;i<84;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        assertEquals(36,a.defenderCount());assertEquals(48,a.reserveCount());
        assertTrue(a.reserveUnits().stream().allMatch(d->d.cell()==null));
        long spent=a.spentGold();double gold=a.coins();
        assertEquals(Arena.Result.FULL,a.summon(a.owner(),new SummonRoll(UnitType.WOLF,Rarity.MIRACLE),(t,r,c)->{fail();return null;}));
        assertEquals(spent,a.spentGold());assertEquals(gold,a.coins());
        var enemy=new Enemy(UUID.randomUUID(),"a",EnemyType.ZOMBIE,1e12,.01,0,false);a.addEnemy(enemy);
        Set<UUID> reserve=new HashSet<>();a.reserveUnits().forEach(d->reserve.add(d.entityId()));
        CombatEngine combat=new CombatEngine();
        for(int tick=0;tick<200;tick++)for(var hit:combat.tick(a,tick))assertFalse(reserve.contains(hit.defender()));
        assertTrue(a.reserveUnits().stream().allMatch(d->d.nextAttackTick()==0));
    }
    @Test void highestGradesReplaceLowerFieldUnitsAndPreserveAttackState() {
        Arena a=arena();a.toggleMerging(a.owner());
        for(int i=0;i<36;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        Defender veteran=a.defenders().getFirst();veteran.attackAt(90,40);veteran.hitTarget(a.owner());
        buy(a,UnitType.RABBIT,Rarity.MIRACLE);Defender miracle=a.lastSummoned();
        assertFalse(miracle.deployed());
        assertEquals(Arena.Result.OK,a.rearrange(a.owner(),new AutoPlacement(a.grid()).arrange(a.units())));
        assertTrue(miracle.deployed());assertEquals(36,a.defenderCount());assertEquals(1,a.reserveCount());
        assertEquals(130,veteran.nextAttackTick());assertEquals(1,veteran.consecutiveHits());
        assertEquals(36,a.defenders().stream().map(Defender::cell).distinct().count());
    }
    @Test void reserveCanSwapWithOccupiedCellWithoutDiscardingEitherUnit() {
        Arena a=arena();a.toggleMerging(a.owner());
        for(int i=0;i<37;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        Defender incoming=a.lastSummoned(),outgoing=a.defenders().getFirst();Cell destination=outgoing.cell();
        outgoing.attackAt(10,30);a.select(a.owner(),incoming.entityId());
        assertEquals(Arena.Result.INVALID_CELL,a.moveSelected(a.owner(),new Cell(-1,0)));
        assertEquals(Arena.Result.NOT_OWNER,a.moveSelected(UUID.randomUUID(),destination));
        assertEquals(Arena.Result.OK,a.moveSelected(a.owner(),destination));
        assertTrue(incoming.deployed());assertFalse(outgoing.deployed());assertEquals(40,outgoing.nextAttackTick());
        assertEquals(37,a.unitCount());
    }
    @Test void matchingPurchaseMergesEvenWhenFieldAndReserveAreFull() {
        Arena a=arena();a.toggleMerging(a.owner());
        for(int i=0;i<84;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        a.toggleMerging(a.owner());buy(a,UnitType.WOLF,Rarity.COMMON);
        assertTrue(a.lastPurchaseMerged());assertTrue(a.unitCount()<84);
        assertEquals(850,a.spentGold());assertEquals(85,a.units().stream().mapToLong(Defender::saleValue).sum());
    }
    @Test void reserveSalesRespectManualOnlyGradesAndPayStoredTierValueOnce() {
        Arena a=arena();a.toggleMerging(a.owner());for(int i=0;i<36;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        a.reachedRound(2500);buy(a,UnitType.PANDA,Rarity.TRUE_PRIMORDIAL);Defender d=a.lastSummoned();
        assertFalse(d.deployed());double before=a.coins();
        assertEquals(Arena.Result.NOT_SELLABLE,a.sellRarity(a.owner(),Rarity.TRUE_PRIMORDIAL).result());
        a.select(a.owner(),d.entityId());assertEquals(Arena.Result.OK,a.sellSelected(a.owner()));
        assertEquals(before+20000,a.coins());assertEquals(Arena.Result.NO_SELECTION,a.sellSelected(a.owner()));
        buy(a,UnitType.PANDA,Rarity.PRIMORDIAL);assertEquals(6000,a.lastSummoned().saleValue());
        assertEquals(6000,a.sellRarity(a.owner(),Rarity.PRIMORDIAL).income());
    }
    @Test void tierBoundariesAreExactAndProbabilitiesPreserveSpecifiedHighGradeYield() {
        Arena a=arena();
        for(int r:new int[]{999,1000,2499,2500,10000}){a.reachedRound(r);assertEquals(r<1000?100:r<2500?1000:10000,a.summonCost());}
        a.reachedRound(1);assertEquals(10000,a.summonCost());
        for(SummonTier tier:SummonTier.values()) {
            int boundary=0;
            for(Rarity grade:Rarity.values()) {
                int weight=tier.weight(grade,false);if(weight==0)continue;
                assertEquals(grade,tier.rarity(boundary,false));assertEquals(grade,tier.rarity(boundary+weight-1,false));boundary+=weight;
            }
            assertEquals(100000,boundary);
            double recovery=Arrays.stream(Rarity.values()).mapToDouble(g->tier.weight(g,false)*tier.saleValue(g)/(double)Rarity.TOTAL_WEIGHT).sum()/tier.cost();
            assertTrue(recovery>.51 && recovery<.53,"Recovery "+tier+": "+recovery);
        }
        assertEquals(1,SummonTier.ASCENDED.weight(Rarity.TRUE_PRIMORDIAL,false));
        assertEquals(0,SummonTier.ASCENDED.weight(Rarity.MIRACLE,false));
        assertEquals(1,SummonTier.MIRACLE.weight(Rarity.MIRACLE,false));
        assertEquals(SummonTier.ASCENDED.weight(Rarity.PRIMORDIAL,false)*10,SummonTier.MIRACLE.weight(Rarity.PRIMORDIAL,false));
    }
    @Test void truePrimordialPromotesToMiracleAtTwentyWithoutResettingCooldown() {
        Arena a=arena();buy(a,UnitType.WOLF,Rarity.TRUE_PRIMORDIAL);Defender d=a.lastSummoned();d.attackAt(10,500);
        for(int i=0;i<20;i++)buy(a,UnitType.WOLF,Rarity.TRUE_PRIMORDIAL);
        assertEquals(Rarity.MIRACLE,d.rarity());assertEquals(0,d.enhancement());assertEquals(510,d.nextAttackTick());
        assertEquals(List.of(Rarity.MIRACLE),a.lastPromotions());assertEquals(420000,d.saleValue());
        for(int i=0;i<21;i++)buy(a,UnitType.WOLF,Rarity.MIRACLE);
        assertEquals(Rarity.MIRACLE,d.rarity());assertEquals(21,d.enhancement());
    }
    @Test void snapshotRetainsReserveGradesPaidValuesSelectionAndCombatSeparation()throws Exception {
        Arena a=arena();a.toggleMerging(a.owner());for(int i=0;i<36;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        a.reachedRound(2500);buy(a,UnitType.PANDA,Rarity.MIRACLE);a.select(a.owner(),a.lastSummoned().entityId());
        Arena copy=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(a),Arena.class);
        assertEquals(36,copy.activeDefenders().size());assertEquals(1,copy.reserveCount());assertEquals(SummonTier.MIRACLE,copy.summonTier());
        assertEquals(a.coins(),copy.coins());assertEquals(a.spentGold(),copy.spentGold());assertFalse(copy.mergingEnabled());
        assertEquals(a.lastSummoned().entityId(),copy.selected().orElseThrow().entityId());
        assertEquals(420000,copy.selected().orElseThrow().saleValue());assertFalse(copy.selected().orElseThrow().deployed());
    }
    @Test void allTenThousandWavesHaveFiniteEnemiesAndCorrectUnlocks() {
        CampaignRules rules=CampaignRules.standard();double total=0;
        for(int round=1;round<=10000;round++) {
            Wave wave=WaveSchedule.create(round,rules);assertEquals(round,wave.round());
            for(var entry:wave.entries()) {
                assertTrue(Double.isFinite(entry.enemy().health()));assertTrue(entry.enemy().health()>0);
                assertTrue(entry.offsetTick()<rules.roundTicks());total+=entry.enemy().reward();
            }
        }
        assertTrue(total>0 && Double.isFinite(total));
    }
    @Test void capacityFailureCannotRerollForFreeAndSurvivesStateTransfer()throws Exception {
        Arena a=arena();a.toggleMerging(a.owner());for(int i=0;i<84;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        a.toggleMerging(a.owner());SummonRoll blocked=new SummonRoll(UnitType.PANDA,Rarity.RARE);
        assertEquals(Arena.Result.FULL,a.summon(a.owner(),blocked,(t,r,c)->{fail();return null;}));
        var noRandom=new java.util.random.RandomGenerator(){public long nextLong(){throw new AssertionError("Rerolled a blocked purchase");}};
        double gold=a.coins();
        for(int i=0;i<100;i++)assertEquals(blocked,SummonRoll.draw(noRandom,a));
        assertEquals(Arena.Result.FULL,a.summon(a.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->{fail();return null;}));
        assertEquals(gold,a.coins());
        Arena restored=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(a),Arena.class);
        assertEquals(blocked,SummonRoll.draw(noRandom,restored));
        restored.select(restored.owner(),restored.reserveUnits().getFirst().entityId());restored.sellSelected(restored.owner());
        assertEquals(Arena.Result.OK,restored.summon(restored.owner(),SummonRoll.draw(noRandom,restored),(t,r,c)->UUID.randomUUID()));
        assertNull(restored.pendingRoll());assertEquals(UnitType.PANDA,restored.lastSummoned().type());
    }
    @Test void aNewDrawTierDiscardsTheOldTierBlockedRoll() {
        Arena a=arena();a.toggleMerging(a.owner());for(int i=0;i<84;i++)buy(a,UnitType.WOLF,Rarity.COMMON);
        a.summon(a.owner(),new SummonRoll(UnitType.PANDA,Rarity.COMMON),(t,r,c)->{fail();return null;});
        assertNotNull(a.pendingRoll());a.reachedRound(1000);assertNull(a.pendingRoll());assertEquals(SummonTier.ASCENDED,a.summonTier());
    }
}
