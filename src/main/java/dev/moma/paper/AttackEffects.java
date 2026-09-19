package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** One shape per actual attack, delivered only to its owner and current spectators. */
final class AttackEffects {
    private final Map<Defender, List<Point>> attacks = new LinkedHashMap<>();
    private static final Particle.DustOptions[] COLORS = Arrays.stream(Rarity.values())
            .map(r -> new Particle.DustOptions(Color.fromRGB(EntityAdapter.rarityColor(r).value()), 0.8f))
            .toArray(Particle.DustOptions[]::new);
    void clear() { attacks.clear(); }
    void hit(Defender defender, Point target) { attacks.computeIfAbsent(defender, key -> new ArrayList<>()).add(target); }
    void render(ArenaMap map, List<Player> viewers) {
        for (var attack : attacks.entrySet()) {
            Defender defender = attack.getKey();
            Particle.DustOptions dust = COLORS[defender.rarity().ordinal()];
            for (Point point : shape(defender, attack.getValue())) {
                double x = map.originX() + point.x() + .5, y = map.floorY() + 1.65, z = map.originZ() + point.z() + .5;
                for (Player viewer : viewers) viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
            }
        }
    }
    static List<Point> shape(Defender defender, List<Point> targets) {
        if (targets.isEmpty()) return List.of();
        var points = new ArrayList<Point>();
        Point primary = targets.getFirst();
        line(points, defender.position(), primary);
        switch (defender.type().role()) {
            case SMALL_AREA, LARGE_AREA -> circle(points, primary, defender.profile().areaRadius()
                    + (defender.type().role() == AttackRole.LARGE_AREA ? .5 * defender.rarity().abilityLevel() : 0));
            case MELEE_CLEAVE -> circle(points, defender.position(), defender.profile().range());
            case MULTI_TARGET -> {
                for (int i = 1; i < targets.size(); i++) line(points, targets.get(i-1), targets.get(i));
            }
            default -> {}
        }
        return points;
    }
    private static void line(List<Point> points, Point a, Point b) {
        int steps = Math.max(1, Math.min(24, (int) Math.ceil(Math.sqrt(a.distanceSquared(b)) / .65)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps; points.add(new Point(a.x() + (b.x()-a.x())*t, a.z() + (b.z()-a.z())*t));
        }
    }
    private static void circle(List<Point> points, Point center, double radius) {
        for (int i = 0; i <= 32; i++) {
            double angle = Math.PI * 2 * i / 32;
            points.add(new Point(center.x() + Math.cos(angle)*radius, center.z() + Math.sin(angle)*radius));
        }
    }
}
