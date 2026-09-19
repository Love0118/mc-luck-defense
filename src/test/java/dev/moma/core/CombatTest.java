package dev.moma.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatTest {
    private final CombatEngine combat = new CombatEngine();
    private final UUID owner = UUID.randomUUID();
    private final Route route = new Grid(3).route();
    private Enemy enemy(String arena, double progress, boolean boss) {
        Enemy enemy = new Enemy(UUID.randomUUID(), arena, EnemyType.ZOMBIE, 100_000, progress == 0 ? 0.001 : progress * 20, 2, boss);
        if (progress > 0) enemy.advance(0);
        return enemy;
    }
    private Defender defender(UnitType type, Rarity rarity) { return new Defender(UUID.randomUUID(), owner, "a", type, rarity, new Cell(0, 0)); }
    private List<Enemy> targets(Defender defender, Enemy... enemies) {
        return combat.targets(defender, List.of(enemies), route, defender.type().profile().at(defender.rarity()));
    }
    @Test void progressIsCumulativeAcrossLapAndDeterminesPriority() {
        Enemy firstLap = enemy("a", 3, false), nextLap = enemy("a", route.length() + 2, false);
        assertEquals(route.at(2), nextLap.position(route));
        assertEquals(nextLap, targets(defender(UnitType.SKELETON, Rarity.COMMON), firstLap, nextLap).getFirst());
        assertEquals(new Point(-3, -3), route.at(route.length()));
    }
    @Test void rangeDeadEnemiesAndArenaMembershipAreRespected() {
        Defender defender = defender(UnitType.WOLF, Rarity.COMMON);
        Enemy valid = enemy("a", 3, false), foreign = enemy("other", 4, false), dead = enemy("a", 5, false), distant = enemy("a", 20, false);
        dead.damage(100_000);
        assertEquals(List.of(valid), targets(defender, foreign, dead, distant, valid));
    }
    @Test void smallAreaLargeAreaAndMultiHaveDifferentGeometry() {
        Enemy center = enemy("a", 4, false), close = enemy("a", 3, false), farther = enemy("a", 1, false), isolated = enemy("a", route.length() - 5, false);
        // Make the impact center the most progressed while preserving its physical position.
        center = enemy("a", route.length() + 4, false);
        assertEquals(2, targets(defender(UnitType.WITCH, Rarity.COMMON), center, close, farther, isolated).size());
        assertEquals(3, targets(defender(UnitType.BLAZE, Rarity.COMMON), center, close, farther, isolated).size());
        var multi = targets(defender(UnitType.EVOKER, Rarity.COMMON), center, close, farther, isolated);
        assertEquals(3, multi.size()); assertTrue(multi.contains(isolated));
        assertEquals(1, targets(defender(UnitType.EVOKER, Rarity.PRIMORDIAL), center).size());
    }
    @Test void meleeCleaveExcludesEnemiesBehindTheDefender() {
        Enemy north = enemy("a", route.length() + 3, false), nearby = enemy("a", 2, false), south = enemy("a", route.length() - 6, false);
        List<Enemy> hits = targets(defender(UnitType.IRON_GOLEM, Rarity.COMMON), north, nearby, south);
        assertEquals(2, hits.size()); assertFalse(hits.contains(south));
    }
    @Test void strongestSlowWinsAndWeakerLongerEffectResumes() {
        Enemy enemy = enemy("a", 0, false);
        enemy.slow(0.2, 100); enemy.slow(0.5, 20); enemy.slow(0.1, 200);
        assertEquals(0.5, enemy.slowAt(19)); assertEquals(0.2, enemy.slowAt(20));
        assertEquals(0.1, enemy.slowAt(100)); assertEquals(0, enemy.slowAt(200));
    }
    @ParameterizedTest @EnumSource(Rarity.class)
    void singleTargetRolesStaySingleAtEveryTier(Rarity rarity) {
        Enemy a = enemy("a", 3, false), b = enemy("a", 4, false);
        assertEquals(1, targets(defender(UnitType.WOLF, rarity), a, b).size());
        assertEquals(1, targets(defender(UnitType.SKELETON, rarity), a, b).size());
    }
    private Arena populated(UnitType type, Rarity rarity) {
        Arena arena = new Arena("a", owner, new Grid(3), 10, 100);
        arena.summon(owner, new SummonRoll(type, rarity), (t, r, c) -> UUID.randomUUID());
        return arena;
    }
    @Test void cooldownAndConsecutiveBonusSurviveMovementButTargetChangeResetsChain() {
        Arena arena = populated(UnitType.WOLF, Rarity.LEGENDARY);
        Enemy first = enemy("a", 0, false);
        // Progress 3 blocks then use a nearly stationary enemy for the combat sequence.
        for (int i = 0; i < 60_000; i++) first.advance(0);
        arena.addEnemy(first);
        Defender defender = arena.defenders().getFirst();
        double hit1 = combat.tick(arena, 0).getFirst().damage();
        long ready = defender.nextAttackTick();
        arena.select(owner, defender.entityId());
        arena.moveSelected(owner, new Cell(1, 0));
        assertTrue(combat.tick(arena, 1).isEmpty()); assertEquals(ready, defender.nextAttackTick());
        double hit2 = combat.tick(arena, ready).getFirst().damage();
        assertTrue(hit2 > hit1);
        first.damage(100_000); arena.collectDeadEnemies();
        Enemy second = enemy("a", 0, false);
        for (int i = 0; i < 120_000; i++) second.advance(0);
        arena.addEnemy(second);
        double reset = combat.tick(arena, defender.nextAttackTick()).getFirst().damage();
        assertEquals(hit1, reset);
    }
    @Test void bossBonusAndCenterBonusAreRoleSpecific() {
        Arena ranged = populated(UnitType.SKELETON, Rarity.LEGENDARY);
        ranged.addEnemy(enemy("a", 1, true));
        double base = UnitType.SKELETON.profile().at(Rarity.LEGENDARY).damage();
        assertEquals(base * 1.5, combat.tick(ranged, 0).getFirst().damage());
        Arena splash = populated(UnitType.WITCH, Rarity.LEGENDARY);
        splash.addEnemy(enemy("a", 1, false)); splash.addEnemy(enemy("a", 0.8, false));
        List<CombatEngine.Hit> hits = combat.tick(splash, 0);
        assertEquals(2, hits.size());
        assertEquals(UnitType.WITCH.profile().at(Rarity.LEGENDARY).damage() * 1.3, hits.getFirst().damage());
        assertEquals(UnitType.WITCH.profile().at(Rarity.LEGENDARY).damage(), hits.getLast().damage());
    }
    @Test void higherSpecialLevelsIncreaseAreaAndDistinctTargets() {
        Enemy a = enemy("a", route.length() + 5, false), b = enemy("a", 0.5, false);
        assertEquals(1, targets(defender(UnitType.BLAZE, Rarity.COMMON), a, b).size());
        assertEquals(2, targets(defender(UnitType.BLAZE, Rarity.PRIMORDIAL), a, b).size());
        Enemy[] crowd = new Enemy[8];
        for (int i = 0; i < crowd.length; i++) crowd[i] = enemy("a", 1 + i * 0.1, false);
        assertEquals(3, targets(defender(UnitType.EVOKER, Rarity.COMMON), crowd).size());
        assertEquals(7, targets(defender(UnitType.EVOKER, Rarity.PRIMORDIAL), crowd).size());
    }
}
