package dev.moma.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignTest {
    @Test void survivalCheckpointCountsTheLastTickAndExcludesDefeatOnThatTick() {
        var rules = new CampaignRules(6, 0, 10_000, 3, 100, 40, 1, CampaignRules.standard().healthCurve(), 1,3.4,.8);
        for (int defeatTick : new int[]{3001, 3002, 3003}) {
            var arena = new Arena("a", UUID.randomUUID(), new Grid(6), 0, 10_000);
            var campaign = new Campaign(rules);
            for (int tick = 0; tick <= defeatTick; tick++) {
                campaign.beforeCombat(arena, spec -> UUID.randomUUID());
                if (tick == defeatTick) arena.finish(Arena.Outcome.ENEMY_LIMIT);
                campaign.afterCombat(arena);
            }
            assertEquals(defeatTick <= 3002 ? 29 : 30, campaign.completedRounds());
        }
        var arena = new Arena("a", UUID.randomUUID(), new Grid(6), 0, 10_000);
        var campaign = new Campaign(rules);
        for (int tick = 0; tick <= 3002; tick++) {
            campaign.beforeCombat(arena, spec -> UUID.randomUUID());
            campaign.afterCombat(arena);
            if (tick == 3001) assertEquals(29, campaign.completedRounds());
        }
        assertEquals(30, campaign.completedRounds());
        assertTrue(arena.enemyCount() > 0); // Surviving a round does not require an empty circuit.
    }
    @Test void bossHealthCanBeTunedWithoutChangingOrdinaryEnemiesOrRewards() {
        var rules = CampaignRules.standard();
        var baseline = WaveSchedule.create(rules);
        var changed = WaveSchedule.create(rules.withBossHealthScale(rules.bossHealthScale() / 2));
        for (int wave = 0; wave < 100; wave++) for (int i = 0; i < baseline.get(wave).entries().size(); i++) {
            var before = baseline.get(wave).entries().get(i).enemy();
            var after = changed.get(wave).entries().get(i).enemy();
            assertEquals(before.health() * (before.boss() ? .5 : 1), after.health(), 1e-8);
            assertEquals(before.reward(), after.reward());
        }
        assertThrows(IllegalArgumentException.class, () -> rules.withBossHealthScale(0));
        assertThrows(IllegalArgumentException.class, () -> rules.withBossHealthScale(Double.NaN));
    }
    @Test void waveHealthUsesSharedCurveAndScale() {
        var rules = CampaignRules.standard();
        var normal = WaveSchedule.create(rules);
        var scaled = WaveSchedule.create(rules.withHealthScale(rules.healthScale() * 2));
        for (int round = 1; round <= 100; round++) {
            double ratio = scaled.get(round - 1).entries().getFirst().enemy().health() / normal.get(round - 1).entries().getFirst().enemy().health();
            assertEquals(2, ratio, 1e-10);
        }
        assertEquals(6, rules.gridSize());
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
        assertEquals(10, bosses); assertEquals(EnumSet.allOf(EnemyType.class),types);
    }
    @Test void preparationSpawnsAndRoundBoundariesAreExactAndSurvivorsPersist() {
        CampaignRules rules = new CampaignRules(5, 100, 1000, 3, 100, 100, 1, CampaignRules.standard().healthCurve(), 1,3.4,.8);
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
        CampaignRules rules = new CampaignRules(5, 100, 100, 0, 100, 100, 1, CampaignRules.standard().healthCurve(), 1,3.4,.8);
        Arena arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 100);
        Campaign campaign = new Campaign(rules); long[] id = {0};
        for (int tick = 0; tick <= rules.maximumTicks() && !arena.ended(); tick++) {
            campaign.beforeCombat(arena, spec -> new UUID(0, ++id[0]));
            for (Enemy enemy : arena.enemies()) enemy.damage(enemy.health());
            arena.collectDeadEnemies(); campaign.afterCombat(arena);
            if (campaign.round() < 100) assertFalse(arena.ended());
        }
        assertEquals(Arena.Outcome.VICTORY, arena.outcome()); assertEquals(100, campaign.round());
        assertEquals(100, campaign.completedRounds());
        assertEquals(0, arena.enemyCount());
        int spawned = (int) WaveSchedule.create(rules).stream().mapToLong(w -> w.entries().size()).sum();
        assertEquals(spawned, id[0]);
    }
    @Test void finalEnemyMustDieBeforeCleanupDeadline() {
        CampaignRules rules = new CampaignRules(5, 100, 10_000, 0, 100, 40, 1, CampaignRules.standard().healthCurve(), 1,3.4,.8);
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
        var rules = new CampaignRules(5, 100, 2, 0, 100, 40, 1, CampaignRules.standard().healthCurve(), 1,3.4,.8);
        var arena = new Arena("a", UUID.randomUUID(), new Grid(5), 100, 2);
        var campaign = new Campaign(rules);
        assertThrows(IllegalStateException.class, () -> campaign.beforeCombat(arena, spec -> { throw new IllegalStateException(); }));
        campaign.beforeCombat(arena, spec -> UUID.randomUUID()); assertEquals(1, arena.enemyCount());
        while (!arena.ended()) campaign.beforeCombat(arena, spec -> UUID.randomUUID());
        long elapsed = campaign.elapsed();
        campaign.beforeCombat(arena, spec -> { fail("must not spawn after defeat"); return null; });
        assertEquals(elapsed, campaign.elapsed()); assertEquals(Arena.Outcome.ENEMY_LIMIT, arena.outcome());
    }
}
