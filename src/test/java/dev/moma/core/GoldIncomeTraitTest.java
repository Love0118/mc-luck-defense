package dev.moma.core;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoldIncomeTraitTest {
    private Arena arena(long gold,String...ids) {
        return new Arena("income",new UUID(0,1),new Grid(6),gold,100,new TraitLoadout(List.of(ids)),new HashRandom(42));
    }
    private Enemy enemy(Arena arena,double reward,boolean boss) {
        Enemy enemy=new Enemy(UUID.randomUUID(),arena.id(),EnemyType.ZOMBIE,1,1,reward,boss);
        arena.addEnemy(enemy);return enemy;
    }
    private void kill(Arena arena,double reward,boolean boss) {
        enemy(arena,reward,boss).damage(1);assertEquals(1,arena.collectDeadEnemies().size());
    }
    @Test void everyTierAccumulatesSubTenthRewardsWithoutLossOrDuplicatePayment() {
        for(var entry:TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.GOLD_INCOME).toList()) {
            Arena arena=arena(30,entry.id());
            for(int i=1;i<=1000;i++) {
                kill(arena,.1,i%10==0);
                long expected=i+(long)i*entry.value()/100;
                assertEquals(Gold.amount(expected),arena.earnedCoins(),1e-9);
                assertEquals(30+Gold.amount(expected),arena.coins(),1e-9);
                assertTrue(arena.collectDeadEnemies().isEmpty());
                assertEquals(Gold.amount(expected),arena.earnedCoins(),1e-9);
            }
        }
    }
    @Test void saleRefundsStartingGoldAndManualCreditsAreNotMultiplied() {
        Arena arena=arena(30,"gold_spent_10000000","round_100");
        assertEquals(40,arena.coins());
        arena.credit(10);assertEquals(50,arena.coins());
        assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID()));
        arena.select(arena.owner(),arena.lastSummoned().entityId());
        assertEquals(Arena.Result.OK,arena.sellSelected(arena.owner()));
        assertEquals(41,arena.coins());assertEquals(0,arena.earnedCoins());
        assertEquals(10,arena.spentGold());
        assertEquals(1,arena.damageMultiplier(AttackRole.MELEE_SINGLE,false));
        assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(UnitType.PANDA,Rarity.RARE),(t,r,c)->UUID.randomUUID()));
        arena.sellRarity(arena.owner(),Rarity.RARE);assertEquals(34,arena.coins());
    }
    @Test void fractionalCarrySurvivesSnapshotRestoreAndIncludesBossRewards()throws Exception {
        Arena arena=arena(0,"gold_spent_1000");
        kill(arena,.1,false);
        Arena restored=copy(arena);
        for(int i=0;i<999;i++){kill(arena,.1,i%2==0);kill(restored,.1,i%2==0);}
        assertEquals(arena.coins(),restored.coins());
        assertEquals(100+TraitCatalog.find("gold_spent_1000").value(),restored.coins());
        kill(restored,7.7,true);
        long base=1077;
        assertEquals(Gold.amount(base+base*restored.traits().value(TraitCatalog.Family.GOLD_INCOME)/100),restored.earnedCoins());
    }
    @Test void zeroAndAlreadyClaimedRewardsDoNotDuplicateIncome() {
        Arena arena=arena(0,"gold_spent_10000000");
        kill(arena,.1,false);double earned=arena.earnedCoins();
        kill(arena,0,false);assertEquals(earned,arena.earnedCoins());
        Enemy claimed=enemy(arena,100,true);claimed.damage(1);claimed.claimRewardUnits();
        arena.collectDeadEnemies();assertEquals(earned,arena.earnedCoins());
    }
    @Test void oldSpendingTraitSnapshotsMigrateByStableAchievementId()throws Exception {
        String id="gold_spent_10000000";
        TraitLoadout traits=new TraitLoadout(List.of(id));
        var current=TraitCatalog.find(id);
        var old=new TraitCatalog.Entry(id,"전장의 투자 · 12%",TraitCatalog.Family.SPENDING_DAMAGE,12,null,Rarity.LEGENDARY,null,current.achievement());
        var entries=TraitLoadout.class.getDeclaredField("entries");entries.setAccessible(true);entries.set(traits,List.of(old));
        var values=TraitLoadout.class.getDeclaredField("values");values.setAccessible(true);
        int[] legacy=new int[TraitCatalog.Family.GOLD_INCOME.ordinal()];legacy[TraitCatalog.Family.SPENDING_DAMAGE.ordinal()]=12;values.set(traits,legacy);
        TraitLoadout restored=copy(traits);
        assertEquals(List.of(id),restored.ids());assertEquals(0,restored.value(TraitCatalog.Family.SPENDING_DAMAGE));
        assertEquals(current.value(),restored.value(TraitCatalog.Family.GOLD_INCOME));
        assertEquals(TraitCatalog.Family.GOLD_INCOME,restored.entries().getFirst().family());
    }
    @SuppressWarnings("unchecked")
    private <T>T copy(T value)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var output=new ObjectOutputStream(bytes)){output.writeObject(value);}
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){return (T)input.readObject();}
    }
}
