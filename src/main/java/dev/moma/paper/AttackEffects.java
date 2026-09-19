package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Latest actual attack per defender per server tick, delivered only to its owner and spectators. */
final class AttackEffects {
    record SoundProfile(String key, float volume) {}
    private final Map<Defender, List<Point>> attacks = new LinkedHashMap<>();
    private final Set<Defender> currentStep = new HashSet<>();
    private static final Particle.DustOptions[] COLORS = Arrays.stream(Rarity.values())
            .map(r -> new Particle.DustOptions(Color.fromRGB(EntityAdapter.rarityColor(r).value()), 0.8f))
            .toArray(Particle.DustOptions[]::new);
    void clear() { attacks.clear(); currentStep.clear(); }
    void beginStep() { currentStep.clear(); }
    boolean hit(Defender defender, Point target) {
        List<Point> targets = attacks.computeIfAbsent(defender, key -> new ArrayList<>());
        boolean primary = currentStep.add(defender);
        if (primary) targets.clear();
        targets.add(target); return primary;
    }
    void forEachPrimary(java.util.function.BiConsumer<Defender, Point> action) {
        attacks.forEach((defender, targets) -> action.accept(defender, targets.getFirst()));
    }
    void render(ArenaMap map, List<Player> viewers) {
        for (var attack : attacks.entrySet()) {
            Defender defender = attack.getKey();
            SoundProfile sound = attackSound(defender.type());
            // Anchor audio at each listener so flying owners and spectators hear attacks across the arena.
            for (Player viewer : viewers) viewer.playSound(viewer.getLocation(), sound.key(), SoundCategory.PLAYERS, sound.volume(), 1f);
            Particle.DustOptions dust = COLORS[defender.rarity().ordinal()];
            emit(map, viewers, trace(defender, attack.getValue()), 1.65, dust);
            emit(map, viewers, footprint(defender, attack.getValue().getFirst()), 1.06, dust);
        }
    }
    static SoundProfile attackSound(UnitType type) {
        return switch (type) {
            case WOLF -> new SoundProfile("minecraft:entity.wolf.growl", .12f);
            case POLAR_BEAR -> new SoundProfile("minecraft:entity.polar_bear.warning", .24f);
            case PANDA -> new SoundProfile("minecraft:entity.panda.bite", .22f);
            case RABBIT -> new SoundProfile("minecraft:entity.rabbit.attack", .18f);
            case IRON_GOLEM -> new SoundProfile("minecraft:entity.iron_golem.attack", .28f);
            case HOGLIN -> new SoundProfile("minecraft:entity.hoglin.attack", .28f);
            case GOAT -> new SoundProfile("minecraft:entity.goat.ram_impact", .26f);
            case RAVAGER -> new SoundProfile("minecraft:entity.ravager.attack", .3f);
            case SKELETON -> new SoundProfile("minecraft:entity.skeleton.shoot", .2f);
            case STRAY, BOGGED -> new SoundProfile("minecraft:entity.skeleton.shoot", .2f);
            case PIGLIN -> new SoundProfile("minecraft:entity.piglin.angry", .2f);
            case WITCH -> new SoundProfile("minecraft:entity.witch.throw", .22f);
            case LLAMA -> new SoundProfile("minecraft:entity.llama.spit", .22f);
            case SNOW_GOLEM -> new SoundProfile("minecraft:entity.snow_golem.shoot", .18f);
            case GUARDIAN -> new SoundProfile("minecraft:entity.guardian.attack", .24f);
            case BLAZE -> new SoundProfile("minecraft:entity.blaze.shoot", .22f);
            case GHAST -> new SoundProfile("minecraft:entity.ghast.shoot", .2f);
            case WITHER_SKELETON -> new SoundProfile("minecraft:entity.wither_skeleton.ambient", .2f);
            case WARDEN -> new SoundProfile("minecraft:entity.warden.sonic_boom", .18f);
            case EVOKER -> new SoundProfile("minecraft:entity.evoker.cast_spell", .22f);
            case SHULKER -> new SoundProfile("minecraft:entity.shulker.shoot", .22f);
            case ALLAY -> new SoundProfile("minecraft:entity.allay.item_thrown", .18f);
            case VEX -> new SoundProfile("minecraft:entity.vex.charge", .2f);
        };
    }
    private void emit(ArenaMap map, List<Player> viewers, List<Point> points, double height, Particle.DustOptions dust) {
        for (Point point : points) {
            double x = map.originX() + point.x() + .5, y = map.floorY() + height, z = map.originZ() + point.z() + .5;
            for (Player viewer : viewers) viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
        }
    }
    static List<Point> trace(Defender defender, List<Point> targets) {
        if (targets.isEmpty()) return List.of();
        var points = new ArrayList<Point>();
        line(points, defender.position(), targets.getFirst());
        if (defender.type().role() == AttackRole.MULTI_TARGET)
            for (int i = 1; i < targets.size(); i++) line(points, targets.get(i-1), targets.get(i));
        return points;
    }
    static List<Point> footprint(Defender defender, Point primary) {
        var points = new ArrayList<Point>();
        switch (defender.type().role()) {
            case SMALL_AREA, LARGE_AREA -> circle(points, primary, AttackGeometry.areaRadius(defender, defender.profile()));
            case MELEE_CLEAVE -> {
                Point origin = defender.position(); double range = defender.profile().range();
                if (origin.equals(primary)) circle(points, origin, range);
                else {
                    double direction = Math.atan2(primary.z()-origin.z(), primary.x()-origin.x());
                    double half = AttackGeometry.CLEAVE_HALF_ANGLE_RADIANS;
                    Point first = polar(origin, range, direction-half), last = polar(origin, range, direction+half);
                    line(points, origin, first);
                    for (int i = 0; i <= 32; i++) points.add(polar(origin, range, direction-half + 2*half*i/32));
                    line(points, last, origin);
                }
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
            points.add(polar(center, radius, angle));
        }
    }
    private static Point polar(Point center, double radius, double angle) {
        return new Point(center.x() + Math.cos(angle)*radius, center.z() + Math.sin(angle)*radius);
    }
}
