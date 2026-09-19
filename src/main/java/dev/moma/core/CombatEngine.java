package dev.moma.core;

import java.util.*;

public final class CombatEngine {
    public record Hit(UUID defender, UUID enemy, double damage) {}
    @FunctionalInterface public interface HitSink { void hit(Defender defender, Enemy enemy, double effectiveDamage); }
    private static final Comparator<Enemy> PRIORITY = Comparator.comparingDouble(Enemy::progress).reversed().thenComparing(Enemy::entityId);
    private final ArrayList<Enemy> ordered = new ArrayList<>();
    private final ArrayList<Enemy> selected = new ArrayList<>();

    public List<Hit> tick(Arena arena, long tick) {
        var hits = new ArrayList<Hit>();
        tick(arena, tick, (defender, enemy, damage) -> hits.add(new Hit(defender.entityId(), enemy.entityId(), damage)));
        return hits;
    }
    /** Same tick implementation for live play and simulation, without allocating a hit log when unnecessary. */
    public void tick(Arena arena, long tick, HitSink sink) {
        if (arena.ended()) return;
        for (Enemy enemy : arena.enemyView()) enemy.advance(tick);
        boolean ready = false;
        for (Defender defender : arena.defenderView()) if (tick >= defender.nextAttackTick()) { ready = true; break; }
        if (!ready || arena.enemyCount() == 0) return;
        ordered.clear(); ordered.addAll(arena.enemyView()); ordered.sort(PRIORITY);
        Route route = arena.grid().route();
        for (Defender defender : arena.defenderView()) {
            if (tick < defender.nextAttackTick()) continue;
            CombatProfile profile = defender.profile();
            select(defender, ordered, route, profile, selected);
            if (selected.isEmpty()) continue;
            Enemy primary = selected.getFirst();
            int chain = defender.hitTarget(primary.entityId());
            int level = defender.rarity().abilityLevel();
            for (Enemy target : selected) {
                double damage = profile.damage();
                if (level > 0) {
                    damage *= switch (defender.type().role()) {
                        case MELEE_SINGLE -> 1 + 0.12 * level * (chain - 1);
                        case RANGED_SINGLE -> target.boss() ? 1 + 0.5 * level : 1;
                        case SMALL_AREA -> target == primary ? 1 + 0.3 * level : 1;
                        default -> 1;
                    };
                    if (defender.type().role() == AttackRole.MELEE_CLEAVE) target.slow(0.10 + 0.08 * level, tick + 40);
                }
                double effective = Math.min(target.health(), damage);
                target.damage(damage);
                if (sink != null) sink.hit(defender, target, effective);
            }
            defender.attackAt(tick, profile.intervalTicks());
        }
    }
    public List<Enemy> targets(Defender defender, Collection<Enemy> enemies, Route route, CombatProfile profile) {
        var sorted = new ArrayList<>(enemies); sorted.sort(PRIORITY);
        var result = new ArrayList<Enemy>(); select(defender, sorted, route, profile, result);
        return List.copyOf(result);
    }
    private void select(Defender defender, List<Enemy> enemies, Route route, CombatProfile profile, List<Enemy> result) {
        result.clear();
        Point origin = defender.position();
        double rangeSquared = profile.range() * profile.range();
        Enemy primary = null;
        for (Enemy enemy : enemies) {
            if (eligible(defender, enemy) && origin.distanceSquared(enemy.position(route)) <= rangeSquared) { primary = enemy; break; }
        }
        if (primary == null) return;
        result.add(primary);
        AttackRole role = defender.type().role();
        if (role == AttackRole.MELEE_SINGLE || role == AttackRole.RANGED_SINGLE) return;
        int level = defender.rarity().abilityLevel();
        int limit = profile.targets() + level;
        double radius = profile.areaRadius() + (role == AttackRole.LARGE_AREA ? 0.5 * level : 0);
        for (Enemy enemy : enemies) {
            if (enemy == primary || !eligible(defender, enemy)) continue;
            Point position = enemy.position(route);
            if (role == AttackRole.MULTI_TARGET) {
                if (result.size() >= limit) break;
                if (origin.distanceSquared(position) <= rangeSquared) result.add(enemy);
            } else if (role == AttackRole.MELEE_CLEAVE) {
                if (origin.distanceSquared(position) <= rangeSquared && inCone(origin, primary.position(route), position)) result.add(enemy);
            } else if (primary.position(route).distanceSquared(position) <= radius * radius) result.add(enemy);
        }
    }
    private boolean eligible(Defender defender, Enemy enemy) { return enemy.alive() && enemy.arenaId().equals(defender.arenaId()); }
    private boolean inCone(Point origin, Point facing, Point target) {
        double ax = facing.x() - origin.x(), az = facing.z() - origin.z();
        double bx = target.x() - origin.x(), bz = target.z() - origin.z();
        double lengths = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        return lengths == 0 || (ax * bx + az * bz) / lengths >= 0.5;
    }
}
