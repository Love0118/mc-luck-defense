package dev.moma.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignTest {
    @Test void lateScalingPreservesEarlyWavesAndReachesConfiguredFinalStrength() {
        var rules = CampaignRules.standard();
        var normal = WaveSchedule.create(rules.withLateHealthScale(1));
        var scaled = WaveSchedule.create(rules);
        for (int round = 1; round <= 100; round++) {
            double progress = Math.max(0, (round - 60) / 40.0);
            double expected = 1 + (rules.lateHealthScale() - 1) * progress * progress;
            double ratio = scaled.get(round - 1).entries().getFirst().enemy().health() / normal.get(round - 1).entries().getFirst().enemy().health();
            assertEquals(expected, ratio, 1e-10);
        }
        assertThrows(IllegalArgumentException.class, () -> rules.withLateHealthScale(0.9));
        assertThrows(IllegalArgumentException.class, () -> rules.withLateHealthScale(Double.NaN));
    }
    @Test void scheduleHas100RoundsWithVariedEnemiesAndTenBosses() {
        CampaignRules rules = CampaignRules.standard();
        List<Wave> waves = WaveSchedule.create(rules);
        assertEquals(100, waves.size());
        Set<EnemyType> types = new HashSet<>();
        int bosses = 0;
        for (int i = 0; i < waves.size(); i++) {
            Wave wave = waves.get(i); assertEquals(i + 1, wave.round());
            int previous = -1;
            for (Wave.Entry entry : wave.entries()) {
                assertTrue(entry.offsetTick() >= previous); previous = entry.offsetTick();
                assertTrue(entry.offsetTick() < rules.roundTicks());
                assertTrue(entry.enemy().health() > 0); assertTrue(entry.enemy().speed() > 0);
                assertTrue(entry.enemy().reward() > 0); types.add(entry.enemy().type());
                if (entry.enemy().boss()) bosses++;
            }
            assertEquals(wave.round() % 10 == 0 ? 1 : 0, wave.entries().stream().filter(e -> e.enemy().boss()).count());
        }
        assertEquals(10, bosses); assertEquals(6, types.size());
    }
    @Test void preparationSpawnsAndRoundBoundariesAreExactAndSurvivorsPersist() {
        CampaignRules rules = new CampaignRules(5, 100, 1000, 3, 100, 100, 1, 1);
        Arena arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 1000);
        Campaign campaign = new Campaign(rules); long[] id = {0};
        for (int tick = 0; tick < 3; tick++) campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
        assertEquals(0, arena.enemyCount()); assertEquals(0, campaign.round());
        campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
        assertEquals(1, arena.enemyCount()); assertEquals(1, campaign.round());
        UUID survivor = arena.enemies().getFirst().entityId();
        while (campaign.elapsed() < 103) campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
        assertEquals(2, campaign.round()); assertTrue(arena.hasEntity(survivor));
        assertEquals(WaveSchedule.create(rules).getFirst().entries().size() + 1, arena.enemyCount());
    }
    @Test void clearingEverySpawnWinsOnlyAfterAll100Waves() {
        CampaignRules rules = new CampaignRules(5, 100, 100, 0, 100, 100, 1, 1);
        Arena arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 100);
        Campaign campaign = new Campaign(rules); long[] id = {0};
        for (int tick = 0; tick <= rules.maximumTicks() && !arena.ended(); tick++) {
            campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
            for (Enemy enemy : arena.enemies()) enemy.damage(enemy.health());
            arena.collectDeadEnemies(); campaign.afterCombat(arena);
            if (campaign.round() < 100) assertFalse(arena.ended());
        }
        assertEquals(Arena.Outcome.VICTORY, arena.outcome()); assertEquals(100, campaign.round());
        assertEquals(0, arena.enemyCount());
        int spawned = (int) WaveSchedule.create(rules).stream().mapToLong(w -> w.entries().size()).sum();
        assertEquals(spawned, id[0]);
    }
    @Test void finalEnemyMustDieBeforeCleanupDeadline() {
        CampaignRules rules = new CampaignRules(5, 100, 10_000, 0, 100, 40, 1, 1);
        Arena arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 10_000);
        Campaign campaign = new Campaign(rules); long[] id = {0};
        for (int tick = 0; tick <= rules.maximumTicks(); tick++) {
            campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
            campaign.afterCombat(arena);
        }
        assertEquals(100, campaign.round()); assertTrue(campaign.cleanup());
        assertEquals(Arena.Outcome.TIME_LIMIT, arena.outcome());
    }
    @Test void failedSpawnDoesNotAdvanceSpawnCursorAndEndedArenaDoesNotSpawn() {
        var rules = new CampaignRules(5, 100, 2, 0, 100, 40, 1, 1);
        var arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 2);
        var campaign = new Campaign(rules);
        assertThrows(IllegalStateException.class, () -> campaign.beforeCombat(arena, spec -> { throw new IllegalStateException(); }));
        campaign.beforeCombat(arena, spec -> UUID.randomUUID()); assertEquals(1, arena.enemyCount());
        while (!arena.ended()) campaign.beforeCombat(arena, spec -> UUID.randomUUID());
        int elapsed = campaign.elapsed();
        campaign.beforeCombat(arena, spec -> { fail("must not spawn after defeat"); return null; });
        assertEquals(elapsed, campaign.elapsed()); assertEquals(Arena.Outcome.ENEMY_LIMIT, arena.outcome());
    }
}
