package dev.moma.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TinyCombatTest {
    @Test void commonTowerCanKillTinyHealthEnemyAndEarnItsReward() {
        UUID owner=UUID.randomUUID();var arena=new Arena("tiny",owner,new Grid(6),10,100);
        arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        double damage=arena.lastSummoned().profile().damage();
        var enemy=new Enemy(UUID.randomUUID(),"tiny",EnemyType.ZOMBIE,damage*.5,2,.1,false);
        arena.addEnemy(enemy);var engine=new CombatEngine();
        for(int tick=0;tick<40;tick++){engine.tick(arena,tick);arena.collectDeadEnemies();}
        assertFalse(enemy.alive());assertEquals(0,arena.enemyCount());assertEquals(.1,arena.coins(),1e-12);
    }
}
