package dev.moma.core;

import dev.moma.runtime.StateCodec;
import java.io.Serializable;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateTransferTest {
    private record State(Arena arena,Campaign campaign,HashRandom random) implements Serializable {}
    @Test void transferRetainsPartialRandomBlockAndAllBattleStateThroughSubsequentTicks()throws Exception {
        UUID owner=new UUID(0,1);long[] next={2};
        Arena arena=new Arena("a",owner,new Grid(6),1000,100,new TraitLoadout(List.of("round_350","session_500","true_primordial_1")),new HashRandom(44));
        var random=new HashRandom(99);random.nextInt();random.nextLong();
        for(int i=0;i<7;i++)arena.summon(owner,new SummonRoll(UnitType.SKELETON,Rarity.LEGENDARY),(t,r,c)->new UUID(0,next[0]++));
        var defender=arena.lastSummoned();arena.select(owner,defender.entityId());arena.moveSelected(owner,new Cell(0,0));arena.select(owner,defender.entityId());
        defender.attackAt(17,31,7);defender.hitTarget(new UUID(0,88));defender.hitTarget(new UUID(0,88));
        Enemy enemy=new Enemy(new UUID(0,88),"a",EnemyType.ENDER_DRAGON,1e9,10,.1,true);enemy.slow(.3,130);enemy.damage(27);arena.addEnemy(enemy);
        Campaign campaign=new Campaign(CampaignRules.standard(),true);
        for(int i=0;i<360;i++)campaign.beforeCombat(arena,e->new UUID(0,next[0]++));
        var original=new State(arena,campaign,random);byte[] bytes=StateCodec.write(original);
        State copy=StateCodec.read(bytes,State.class);
        assertEquals(StateCodec.fingerprint(original),StateCodec.fingerprint(copy));
        assertNotSame(arena,copy.arena());assertEquals(arena.selected().orElseThrow().entityId(),copy.arena().selected().orElseThrow().entityId());
        long[] left={next[0]},right={next[0]};CombatEngine engine=new CombatEngine();
        for(int tick=360;tick<700;tick++) {
            campaign.beforeCombat(arena,e->new UUID(0,left[0]++));copy.campaign().beforeCombat(copy.arena(),e->new UUID(0,right[0]++));
            assertEquals(SummonRoll.draw(random,arena),SummonRoll.draw(copy.random(),copy.arena()));
            engine.tick(arena,tick);engine.tick(copy.arena(),tick);
            assertEquals(arena.collectDeadEnemies(),copy.arena().collectDeadEnemies());campaign.afterCombat(arena);copy.campaign().afterCombat(copy.arena());
            assertEquals(StateCodec.fingerprint(original),StateCodec.fingerprint(copy),"tick "+tick);
        }
    }
    @Test void openingProgressAndPaidRewardsAreNotResetByTransfer()throws Exception {
        UUID owner=UUID.randomUUID();Arena arena=new Arena("a",owner,new Grid(6),30,100,new TraitLoadout(List.of("round_350","session_1000")),new HashRandom(3));
        arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        Arena copy=StateCodec.read(StateCodec.write(arena),Arena.class);
        assertEquals(2,copy.openingTraitRemaining());assertEquals(20,copy.coins());
        assertEquals(Rarity.MYTHIC,copy.summonRarity(Rarity.EPIC));
        copy.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        assertEquals(Rarity.EPIC,copy.summonRarity(Rarity.EPIC));assertEquals(2,copy.lastSummoned().saleValue());
        var enemy=new Enemy(UUID.randomUUID(),"a",EnemyType.ZOMBIE,1,1,.1,false);copy.addEnemy(enemy);enemy.damage(1);
        Arena pending=StateCodec.read(StateCodec.write(copy),Arena.class);pending.collectDeadEnemies();double earned=pending.coins();
        Arena paid=StateCodec.read(StateCodec.write(pending),Arena.class);paid.collectDeadEnemies();assertEquals(earned,paid.coins());
    }
    @Test void rejectsUnexpectedSerializedClasses()throws Exception {
        byte[] file=StateCodec.write(new java.io.File("never-opened"));
        assertThrows(java.io.InvalidClassException.class,()->StateCodec.read(file,Object.class));
    }
}
