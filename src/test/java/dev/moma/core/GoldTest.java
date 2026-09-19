package dev.moma.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoldTest {
    @Test void fractionalKillsAccumulateExactlyAndCannotPurchaseBeforeTheBoundary() {
        UUID owner=UUID.randomUUID();Arena arena=new Arena("a",owner,new Grid(6),0,100);
        for(int kill=1;kill<=100;kill++) {
            Enemy enemy=new Enemy(UUID.randomUUID(),"a",EnemyType.ZOMBIE,1,1,.1,false);
            arena.addEnemy(enemy);enemy.damage(1);arena.collectDeadEnemies();
            assertTrue(arena.collectDeadEnemies().isEmpty());
            assertEquals(kill/10.0,arena.coins());assertEquals(kill/10.0,arena.earnedCoins());
            if(kill==99)assertEquals(Arena.Result.INSUFFICIENT_COINS,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->{fail("No spawn below 10 gold");return null;}));
        }
        assertEquals(Arena.Result.OK,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID()));
        assertEquals(0,arena.coins());assertEquals(10,arena.earnedCoins());
    }
    @Test void startAndRewardsUseThirtyGoldAndExactTenths() {
        CampaignRules rules=CampaignRules.standard();assertEquals(30,rules.startingCoins());
        Arena arena=new Arena("a",UUID.randomUUID(),new Grid(6),rules.startingCoins(),100);
        for(int i=0;i<3;i++)assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID()));
        assertEquals(0,arena.coins());
        assertEquals(.1,WaveSchedule.reward(1));assertEquals(.1,WaveSchedule.reward(20));
        for(Wave wave:WaveSchedule.create(rules))for(Wave.Entry entry:wave.entries()) {
            assertEquals(entry.enemy().reward(),Gold.amount(Gold.units(entry.enemy().reward())));
        }
        assertEquals("9.9",Gold.format(9.9));assertEquals("30",Gold.format(30));
        assertThrows(IllegalArgumentException.class,()->Gold.units(Double.NaN));
        assertThrows(ArithmeticException.class,()->Gold.units(.01));
    }
}
