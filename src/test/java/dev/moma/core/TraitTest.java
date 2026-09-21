package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraitTest {
    private Arena arena(long gold,String...ids) {
        return new Arena("a",new UUID(0,1),new Grid(6),gold,100,new TraitLoadout(List.of(ids)),new HashRandom(123));
    }
    private void summon(Arena a,Rarity rarity) {
        assertEquals(Arena.Result.OK,a.summon(a.owner(),new SummonRoll(UnitType.WOLF,rarity),(t,r,c)->UUID.randomUUID()));
    }
    @Test void catalogHasOneRewardPerChallengeAndLoadoutsRespectSlotsAndFamilies() {
        assertEquals(62,TraitCatalog.ALL.size());
        assertEquals(62,TraitCatalog.ALL.stream().map(TraitCatalog.Entry::id).distinct().count());
        assertEquals(AchievementCatalog.ALL.stream().filter(AchievementCatalog.Entry::challenge).count(),TraitCatalog.ALL.size());
        for(int round:new int[]{99,100,249,250,499,500})
            assertEquals(round<100?0:round<250?1:round<500?2:3,TraitCatalog.slots(round));
        assertThrows(IllegalArgumentException.class,()->new TraitLoadout(List.of("round_100","round_150")));
        assertThrows(IllegalArgumentException.class,()->TraitLoadout.unlocked(List.of("round_100"),99,e->true));
        assertThrows(IllegalArgumentException.class,()->TraitLoadout.unlocked(List.of("round_100"),500,e->false));
        assertEquals(3,TraitLoadout.unlocked(List.of("round_100","round_125","enhancement_100"),500,e->true).entries().size());
    }
    @Test void purchaseCeilingTiersKeepTwoUsesAndNeverCreatePrimordial() {
        var legendary=new TraitLoadout(List.of("round_250"));
        var epic=new TraitLoadout(List.of("round_350"));
        assertEquals(Rarity.EPIC,legendary.summonedRarity(Rarity.EPIC,0));
        assertEquals(Rarity.MYTHIC,epic.summonedRarity(Rarity.EPIC,0));
        assertEquals(Rarity.MYTHIC,epic.summonedRarity(Rarity.MYTHIC,0));
        assertEquals(Rarity.MYTHIC,epic.summonedRarity(Rarity.EPIC,1));
        assertEquals(Rarity.EPIC,epic.summonedRarity(Rarity.EPIC,2));
        assertEquals(Rarity.PRIMORDIAL,epic.summonedRarity(Rarity.PRIMORDIAL,0));
        assertThrows(IllegalArgumentException.class,()->new TraitLoadout(List.of("round_250","round_350")));
        int extra=0;
        for(int roll=0;roll<Rarity.TOTAL_WEIGHT;roll++){
            Rarity original=Rarity.fromRoll(roll,true);
            if(original!=Rarity.PRIMORDIAL && epic.summonedRarity(original,0)==Rarity.PRIMORDIAL)extra++;
        }
        assertEquals(0,extra);
    }
    @Test void firstPurchaseIsTransactionalUsesOriginalSaleValueAndOriginalOpeningGrade() {
        Arena a=arena(30,"round_100","round_125");
        assertEquals(40,a.coins());
        var roll=new SummonRoll(UnitType.WOLF,Rarity.ANCIENT);
        assertThrows(IllegalStateException.class,()->a.summon(a.owner(),roll,(t,r,c)->{throw new IllegalStateException();}));
        assertEquals(40,a.coins());assertEquals(Rarity.RELIC,a.summonRarity(Rarity.ANCIENT));
        a.summon(a.owner(),roll,(t,r,c)->{assertEquals(Rarity.RELIC,r);return UUID.randomUUID();});
        assertFalse(a.openingBonusActive());assertEquals(5,a.lastSummoned().saleValue());
        assertEquals(Rarity.COMMON,a.summonRarity(Rarity.COMMON));
        a.select(a.owner(),a.lastSummoned().entityId());a.sellSelected(a.owner());assertEquals(35,a.coins());
        Arena b=arena(30,"round_125");summon(b,Rarity.RARE);
        assertEquals(Rarity.ANCIENT,b.lastSummoned().rarity());assertTrue(b.openingBonusActive());
        Arena c=arena(30,"round_125");summon(c,Rarity.MYTHIC);summon(c,Rarity.COMMON);
        assertEquals(Rarity.COMMON,c.lastSummoned().rarity());
        Arena poor=arena(0,"round_125");
        assertEquals(Arena.Result.INSUFFICIENT_COINS,poor.summon(poor.owner(),roll,(t,r,p)->{fail();return null;}));
        assertEquals(Rarity.RELIC,poor.summonRarity(Rarity.ANCIENT));
    }
    @Test void openingTraitsPreserveBaseBonusAndExactBoundariesWithoutChangingUnitStream() {
        for(String id:List.of("session_100","session_250","session_500","session_1000")) {
            Arena a=arena(100,id);var entry=TraitCatalog.find(id);Rarity target=entry.openingTarget();
            for(boolean baseBonus:new boolean[]{true,false}) {
                int[] counts=new int[Rarity.values().length];
                for(int roll=0;roll<Rarity.TOTAL_WEIGHT;roll++)counts[a.rarityFromRoll(roll).ordinal()]++;
                int sum=0;
                for(Rarity rarity:Rarity.values()) {
                    int weight=a.summonWeight(rarity);assertEquals(weight,counts[rarity.ordinal()]);
                    if(weight>0){assertEquals(rarity,a.rarityFromRoll(sum));assertEquals(rarity,a.rarityFromRoll(sum+weight-1));}sum+=weight;
                }
                assertEquals(100000,sum);assertEquals(entry.value(),a.summonWeight(target));
                assertEquals(baseBonus?30000:10200,a.summonWeight(Rarity.ANCIENT));
                assertEquals(19,a.summonWeight(Rarity.PRIMORDIAL));
                if(baseBonus)summon(a,Rarity.RELIC);
            }
            HashRandom normal=new HashRandom(12),boosted=new HashRandom(12);
            for(int i=0;i<1000;i++)assertEquals(SummonRoll.draw(normal,true).type(),SummonRoll.draw(boosted,a).type());
            assertThrows(IllegalArgumentException.class,()->a.rarityFromRoll(-1));
            assertThrows(IllegalArgumentException.class,()->a.rarityFromRoll(100000));
        }
        assertThrows(IllegalArgumentException.class,()->new TraitLoadout(List.of("session_100","session_1000")));
    }
    @Test void openingTargetUsesRawDrawAndEndsOnHitOrThirdSuccessWithoutPrimordialUpgrade() {
        Arena a=arena(100,"session_500","round_350");
        var target=new SummonRoll(UnitType.WOLF,Rarity.EPIC);
        assertThrows(IllegalStateException.class,()->a.summon(a.owner(),target,(t,r,c)->{throw new IllegalStateException();}));
        assertEquals(3,a.openingTraitRemaining());
        summon(a,Rarity.LEGENDARY);assertEquals(Rarity.EPIC,a.lastSummoned().rarity());assertTrue(a.openingTraitActive());
        summon(a,Rarity.EPIC);assertEquals(Rarity.MYTHIC,a.lastSummoned().rarity());assertFalse(a.openingTraitActive());
        a.sellRarity(a.owner(),Rarity.MYTHIC);assertFalse(a.openingTraitActive());
        assertEquals(200,a.summonWeight(Rarity.EPIC));
        Arena b=arena(100,"session_1000","round_350");
        summon(b,Rarity.MYTHIC);assertEquals(Rarity.MYTHIC,b.lastSummoned().rarity());assertFalse(b.openingTraitActive());
        Arena c=arena(100,"session_1000");
        for(int i=0;i<3;i++){assertTrue(c.openingTraitActive());summon(c,Rarity.COMMON);}
        assertFalse(c.openingTraitActive());assertEquals(80,c.summonWeight(Rarity.MYTHIC));
        Arena advanced=arena(100,"session_1000");advanced.reachedRound(101);
        assertFalse(advanced.openingTraitActive());assertEquals(800,advanced.summonWeight(Rarity.MYTHIC));
        Arena poor=arena(0,"session_1000");
        assertEquals(Arena.Result.INSUFFICIENT_COINS,poor.summon(poor.owner(),target,(t,r,c2)->UUID.randomUUID()));
        assertEquals(3,poor.openingTraitRemaining());
    }
    @Test void upgradedDuplicateUsesOriginalMaterialPriceAndConsumesOnlySuccessfulPurchases() {
        Arena a=arena(50,"round_250");
        summon(a,Rarity.COMMON);assertFalse(a.lastPurchaseMerged());
        summon(a,Rarity.COMMON);assertTrue(a.lastPurchaseMerged());
        assertEquals(Rarity.RARE,a.lastSummoned().rarity());assertEquals(1,a.lastSummoned().enhancement());
        assertEquals(2,a.lastSummoned().saleValue());summon(a,Rarity.COMMON);
        assertEquals(Rarity.COMMON,a.lastSummoned().rarity());
    }
    @Test void enhancementAddsPercentagePointsAndSurvivesPromotionWithoutChangingPrice() {
        Arena normal=arena(1000),boosted=arena(1000,"enhancement_500");
        summon(normal,Rarity.COMMON);summon(boosted,Rarity.COMMON);
        double base=normal.lastSummoned().profile().damage();
        for(int i=0;i<5;i++){summon(normal,Rarity.COMMON);summon(boosted,Rarity.COMMON);}
        assertEquals(base*.5,boosted.lastSummoned().profile().damage()-normal.lastSummoned().profile().damage(),1e-9);
        double before=boosted.lastSummoned().profile().damage();
        for(int i=5;i<20;i++)summon(boosted,Rarity.COMMON);
        assertEquals(Rarity.RARE,boosted.lastSummoned().rarity());
        assertTrue(boosted.lastSummoned().profile().damage()>before);
        assertEquals(base*25,boosted.lastSummoned().profile().damage(),1e-9);
        assertEquals(21,boosted.lastSummoned().saleValue());
    }
    @Test void fractionalAttackSpeedAccumulatesWithoutMoveOrMergeResettingCooldown() {
        Defender d=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",UnitType.WOLF,Rarity.COMMON,new Cell(0,0));
        long tick=0;
        for(int i=0;i<102;i++){d.attackAt(tick,20,2);tick=d.nextAttackTick();}
        assertEquals(2000,tick,1);
        d.move(new Cell(1,1));d.merge();assertEquals(tick,d.nextAttackTick());
    }
    @Test void conditionalBonusesAddAndCriticalRollOccursOncePerAttack() {
        var loadout=new TraitLoadout(List.of("round_200","primordial_1","true_primordial_1"));
        assertEquals(1.10,loadout.damageMultiplier(AttackRole.MULTI_TARGET,true),1e-9);
        assertEquals(1.04,loadout.damageMultiplier(AttackRole.MULTI_TARGET,false),1e-9);
        int[] draws={0};
        var random=new java.util.random.RandomGenerator(){
            public long nextLong(){throw new AssertionError();}
            public int nextInt(int bound){draws[0]++;return 0;}
        };
        Arena a=new Arena("a",new UUID(0,1),new Grid(6),30,100,loadout,random);
        a.summon(a.owner(),new SummonRoll(UnitType.EVOKER,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        a.select(a.owner(),a.lastSummoned().entityId());a.moveSelected(a.owner(),new Cell(0,0));
        for(int i=0;i<2;i++)a.addEnemy(new Enemy(UUID.randomUUID(),"a",EnemyType.ZOMBIE,10000,.001,0,false));
        var hits=new CombatEngine().tick(a,0);assertEquals(2,hits.size());assertEquals(1,draws[0]);
        for(var hit:hits)assertEquals(a.lastSummoned().profile().damage()*1.04*1.5,hit.damage(),1e-9);
        assertTrue(a.roleAchievement(AttackRole.MULTI_TARGET));assertFalse(a.roleAchievement(AttackRole.MELEE_SINGLE));
    }
    @Test void roleShareUsesClampedDamageAndThresholdIsInclusive() {
        Arena a=arena(30);assertFalse(a.roleAchievement(AttackRole.MELEE_SINGLE));
        a.recordDamage(AttackRole.MELEE_SINGLE,70);a.recordDamage(AttackRole.LARGE_AREA,30);
        assertTrue(a.roleAchievement(AttackRole.MELEE_SINGLE));
        a.recordDamage(AttackRole.LARGE_AREA,.01);assertFalse(a.roleAchievement(AttackRole.MELEE_SINGLE));
    }
}
