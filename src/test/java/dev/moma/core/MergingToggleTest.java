package dev.moma.core;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MergingToggleTest {
    private Arena arena(){return new Arena("a",UUID.randomUUID(),new Grid(6),1000,100);}
    private Arena.Result buy(Arena arena,Rarity rarity) {
        return arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,rarity),(t,r,c)->UUID.randomUUID());
    }
    @Test void defaultMergesAndDisablingSpawnsSeparateUnenhancedTowers() {
        Arena arena=arena();assertTrue(arena.mergingEnabled());
        buy(arena,Rarity.EPIC);buy(arena,Rarity.EPIC);
        Defender first=arena.lastSummoned();assertEquals(1,first.enhancement());assertTrue(arena.lastPurchaseMerged());
        assertEquals(Arena.Result.OK,arena.toggleMerging(arena.owner()));
        buy(arena,Rarity.EPIC);assertFalse(arena.lastPurchaseMerged());
        assertEquals(2,arena.defenderCount());assertEquals(1,first.enhancement());assertEquals(0,arena.lastSummoned().enhancement());
        assertNotEquals(first.cell(),arena.lastSummoned().cell());assertEquals(970,arena.coins());
    }
    @Test void enablingDoesNotMergeUntilAnotherPurchaseOrResetTheAttackCooldown() {
        Arena arena=arena();arena.toggleMerging(arena.owner());
        buy(arena,Rarity.LEGENDARY);Defender first=arena.lastSummoned();
        first.attackAt(100,40,0);buy(arena,Rarity.LEGENDARY);UUID second=arena.lastSummoned().entityId();
        arena.select(arena.owner(),second);
        arena.toggleMerging(arena.owner());assertEquals(2,arena.defenderCount());
        assertEquals(0,first.enhancement());assertEquals(140,first.nextAttackTick());
        buy(arena,Rarity.LEGENDARY);assertEquals(1,arena.defenderCount());assertEquals(2,first.enhancement());
        assertEquals(List.of(second),arena.collectMergedEntities());assertEquals(first,arena.selected().orElseThrow());
        assertEquals(140,first.nextAttackTick());assertTrue(arena.lastPurchaseMerged());
    }
    @Test void disabledMergingRespectsCapacityAndAutoSaleCanStillFreeSlots() {
        Arena arena=arena();arena.toggleMerging(arena.owner());
        for(int i=0;i<84;i++)assertEquals(Arena.Result.OK,buy(arena,Rarity.COMMON));
        assertEquals(36,arena.defenderCount());assertEquals(36,arena.defenders().stream().map(Defender::cell).distinct().count());
        assertEquals(Arena.Result.FULL,buy(arena,Rarity.COMMON));assertEquals(160,arena.coins());
        assertEquals(84,arena.sellRarity(arena.owner(),Rarity.COMMON).entities().size());assertEquals(244,arena.coins());
        assertEquals(Arena.Result.OK,buy(arena,Rarity.COMMON));assertFalse(arena.lastPurchaseMerged());
    }
    @Test void onlyOwnerOfActiveArenaCanToggleAndStateSurvivesSerialization()throws Exception {
        Arena arena=arena(),other=arena();
        assertEquals(Arena.Result.NOT_OWNER,arena.toggleMerging(other.owner()));assertTrue(arena.mergingEnabled());
        arena.toggleMerging(arena.owner());assertTrue(other.mergingEnabled());
        var bytes=new ByteArrayOutputStream();try(var output=new ObjectOutputStream(bytes)){output.writeObject(arena);}
        Arena restored;try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){restored=(Arena)input.readObject();}
        assertFalse(restored.mergingEnabled());buy(restored,Rarity.EPIC);buy(restored,Rarity.EPIC);assertEquals(2,restored.defenderCount());
        restored.finish(Arena.Outcome.ENEMY_LIMIT);assertEquals(Arena.Result.ENDED,restored.toggleMerging(restored.owner()));assertFalse(restored.mergingEnabled());
    }
}
