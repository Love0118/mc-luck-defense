package dev.moma.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeCombatTest {
    @Test void nativeBatchMatchesJavaHitsHealthSlowAndCooldownAcrossAllRolesAndTiers() {
        assumeTrue(!System.getProperty("mud.native.library", "").isBlank(), "Optional Rust library not requested");
        assertTrue(CombatEngine.nativeAvailable(), "Requested native library must load");
        for (int seed = 0; seed < 30; seed++) {
            Arena javaArena = fixture(seed), rustArena = fixture(seed);
            CombatEngine javaEngine = new CombatEngine(false), rustEngine = new CombatEngine(true);
            for (int tick = 0; tick < 400; tick++) {
                assertEquals(javaEngine.tick(javaArena,tick), rustEngine.tick(rustArena,tick), "hits seed="+seed+" tick="+tick);
                var expected = javaArena.enemies(); var actual = rustArena.enemies();
                for (int e = 0; e < expected.size(); e++) {
                    assertEquals(expected.get(e).health(),actual.get(e).health());
                    assertEquals(expected.get(e).progress(),actual.get(e).progress());
                    assertEquals(expected.get(e).slowAt(tick),actual.get(e).slowAt(tick));
                }
                var j = javaArena.defenders(); var r = rustArena.defenders();
                for (int d = 0; d < j.size(); d++) {
                    assertEquals(j.get(d).nextAttackTick(),r.get(d).nextAttackTick());
                    assertEquals(j.get(d).consecutiveHits(),r.get(d).consecutiveHits());
                    assertEquals(j.get(d).lastTarget(),r.get(d).lastTarget());
                }
            }
            assertTrue(rustEngine.nativeBatches() > 0, "Native path must execute, not silently fall back");
        }
    }
    private Arena fixture(int seed) {
        UUID owner = new UUID(0,1);
        Arena arena = new Arena("native-check",owner,new Grid(6),360,100);
        for (int i = 0; i < 36; i++) {
            UUID id = new UUID(1,i);
            arena.summon(owner,new SummonRoll(UnitType.values()[(i+seed)%24],Rarity.values()[(i+seed)%9]),(t,r,c)->id);
        }
        for (int i = 0; i < 90; i++) {
            Enemy enemy = new Enemy(new UUID(2,i),arena.id(),EnemyType.values()[i%6], i%7==0?1:100_000 + seed*1_000,1+i%4,6,i%11==0);
            for (int tick = 0; tick < i*11; tick++) enemy.advance(tick);
            arena.addEnemy(enemy);
        }
        return arena;
    }
}
