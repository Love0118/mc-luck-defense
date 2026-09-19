package dev.moma.core;

import java.util.*;

public final class CombatEngine {
    public record Hit(UUID defender, UUID enemy, double damage) {}

    public List<Hit> tick(Arena arena, long tick) {
        if (arena.ended()) return List.of();
        Route route = arena.grid().route();
        for (Enemy enemy : arena.enemies()) enemy.advance(tick);
        var hits = new ArrayList<Hit>();
        for (Defender defender : arena.defenders()) {
            if (tick < defender.nextAttackTick()) continue;
            CombatProfile profile = defender.type().profile().at(defender.rarity());
            List<Enemy> targets = targets(defender, arena.enemies(), route, profile);
            if (targets.isEmpty()) continue;
            Enemy primary = targets.getFirst();
            int chain = defender.hitTarget(primary.entityId());
            int level = defender.rarity().abilityLevel();
            for (Enemy target : targets) {
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
                target.damage(damage);
                hits.add(new Hit(defender.entityId(), target.entityId(), damage));
            }
            defender.attackAt(tick, profile.intervalTicks());
        }
        return List.copyOf(hits);
    }

    public List<Enemy> targets(Defender defender, Collection<Enemy> enemies, Route route, CombatProfile profile) {
        Point origin = defender.position();
        List<Enemy> alive = enemies.stream().filter(Enemy::alive)
                .filter(e -> e.arenaId().equals(defender.arenaId()))
                .sorted(Comparator.comparingDouble(Enemy::progress).reversed().thenComparing(Enemy::entityId)).toList();
        List<Enemy> inRange = alive.stream().filter(e -> origin.distanceSquared(e.position(route)) <= profile.range() * profile.range()).toList();
        if (inRange.isEmpty()) return List.of();
        Enemy primary = inRange.getFirst();
        AttackRole role = defender.type().role();
        int level = defender.rarity().abilityLevel();
        if (role == AttackRole.MELEE_SINGLE || role == AttackRole.RANGED_SINGLE) return List.of(primary);
        if (role == AttackRole.MULTI_TARGET) return inRange.stream().limit(profile.targets() + level).toList();
        var result = new ArrayList<Enemy>();
        result.add(primary);
        if (role == AttackRole.MELEE_CLEAVE) {
            Point direction = primary.position(route);
            for (Enemy enemy : inRange) {
                if (enemy != primary && inCone(origin, direction, enemy.position(route))) result.add(enemy);
            }
        } else {
            double radius = profile.areaRadius() + (role == AttackRole.LARGE_AREA ? 0.5 * level : 0);
            for (Enemy enemy : alive) {
                if (enemy != primary && primary.position(route).distanceSquared(enemy.position(route)) <= radius * radius) result.add(enemy);
            }
        }
        return List.copyOf(result);
    }

    private boolean inCone(Point origin, Point facing, Point target) {
        double ax = facing.x() - origin.x(), az = facing.z() - origin.z();
        double bx = target.x() - origin.x(), bz = target.z() - origin.z();
        double lengths = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        return lengths == 0 || (ax * bx + az * bz) / lengths >= 0.5; // 120 degree cone
    }
}
