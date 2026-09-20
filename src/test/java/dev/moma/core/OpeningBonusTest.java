package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpeningBonusTest {
    private Arena arena() { return new Arena("a",UUID.randomUUID(),new Grid(6),100,100); }
    private void summon(Arena a,Rarity rarity) {
        assertEquals(Arena.Result.OK,a.summon(a.owner(),new SummonRoll(UnitType.WOLF,rarity),(t,r,c)->UUID.randomUUID()));
    }
    @Test void boostedWeightsAndEveryBoundaryAreExact() {
        int[] counts=new int[Rarity.values().length];
        for(int i=0;i<Rarity.TOTAL_WEIGHT;i++) counts[Rarity.fromRoll(i,true).ordinal()]++;
        assertArrayEquals(new int[]{20301,33100,30000,15000,800,500,200,80,19},counts);
        int boundary=0;
        for(Rarity r:Rarity.values()) {
            assertEquals(r,Rarity.fromRoll(boundary,true));boundary+=r.weight(true);
            assertEquals(r,Rarity.fromRoll(boundary-1,true));
        }
        assertEquals(100000,boundary);
        assertThrows(IllegalArgumentException.class,()->Rarity.fromRoll(-1,true));
        assertThrows(IllegalArgumentException.class,()->Rarity.fromRoll(100000,true));
    }
    @Test void expiresAfterThreeSuccessfulSummonsAndHitEndsImmediatelyEvenAfterSale() {
        Arena a=arena(); assertEquals(3,a.openingDrawsRemaining());
        summon(a,Rarity.COMMON);assertEquals(2,a.openingDrawsRemaining());
        summon(a,Rarity.RARE);assertEquals(1,a.openingDrawsRemaining());
        summon(a,Rarity.NARRATIVE);assertFalse(a.openingBonusActive());
        for(Rarity hit:List.of(Rarity.ANCIENT,Rarity.RELIC)) for(int attempt=0;attempt<3;attempt++) {
            Arena b=arena();for(int i=0;i<attempt;i++)summon(b,Rarity.COMMON);
            summon(b,hit); assertFalse(b.openingBonusActive());
            b.sellRarity(b.owner(),hit);assertEquals(0,b.openingDrawsRemaining());
        }
        Arena c=arena();summon(c,Rarity.PRIMORDIAL);assertTrue(c.openingBonusActive());
        assertTrue(arena().openingBonusActive());
    }
    @Test void rejectedAndFailedSpawnsDoNotConsumeOpeningDraws() {
        Arena a=arena();SummonRoll roll=new SummonRoll(UnitType.WOLF,Rarity.RELIC);
        assertEquals(Arena.Result.NOT_OWNER,a.summon(UUID.randomUUID(),roll,(t,r,c)->UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->a.summon(a.owner(),roll,(t,r,c)->{throw new IllegalStateException();}));
        assertEquals(3,a.openingDrawsRemaining());assertEquals(100,a.coins());
        Arena poor=new Arena("p",UUID.randomUUID(),new Grid(6),9,100);
        assertEquals(Arena.Result.INSUFFICIENT_COINS,poor.summon(poor.owner(),roll,(t,r,c)->UUID.randomUUID()));
        assertEquals(3,poor.openingDrawsRemaining());
    }
    @Test void unitStreamStaysIndependentAndUnchangedByGradeBonus() {
        HashRandom normal=new HashRandom(42),boosted=new HashRandom(42);
        int changed=0;
        for(int i=0;i<10000;i++) {
            SummonRoll a=SummonRoll.draw(normal),b=SummonRoll.draw(boosted,true);
            assertEquals(a.type(),b.type());
            if(a.rarity()!=b.rarity())changed++;
        }
        assertTrue(changed>0);
    }
}
