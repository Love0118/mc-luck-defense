package dev.moma.mining;

import java.util.*;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** Fixed shell and permanent routes surround an irregular, connected walkable cavern. */
final class CaveLayout {
    static final int RADIUS = 24, FLOOR = 48, ROOF = 63;
    record Cell(int x, int y, int z) {
        List<Cell> neighbors() {
            return List.of(new Cell(x+1,y,z), new Cell(x-1,y,z), new Cell(x,y+1,z),
                    new Cell(x,y-1,z), new Cell(x,y,z+1), new Cell(x,y,z-1));
        }
    }
    static boolean inside(Cell c) {
        return Math.abs(c.x) <= RADIUS && Math.abs(c.z) <= RADIUS && c.y >= FLOOR && c.y <= ROOF;
    }
    static boolean hub(Cell c) { return Math.abs(c.x) <= 6 && c.z >= -22 && c.z <= -13; }
    static boolean route(Cell c) {
        double r = Math.hypot(c.x, c.z);
        return hub(c) || Math.abs(c.x) <= 2 || Math.abs(c.z) <= 2 || r >= 13 && r <= 16;
    }
    static boolean air(Cell c) {
        if (c.y <= FLOOR || c.y >= ROOF - 1) return false;
        if (route(c) && c.y <= FLOOR + 4) return true;
        double r = Math.hypot(c.x, c.z);
        double shape = 10 + 1.5 * Math.sin(Math.atan2(c.z, c.x) * 5);
        return r <= shape && c.y <= FLOOR + 7;
    }
    static boolean mineable(Cell c) {
        return Math.abs(c.x) < RADIUS - 1 && Math.abs(c.z) < RADIUS - 1
                && c.y > FLOOR && c.y < ROOF - 2 && !air(c) && !hub(c)
                && !(route(c) && c.y <= FLOOR + 5);
    }
    static List<Cell> cells() {
        var result = new ArrayList<Cell>();
        for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++)
            for (int y = FLOOR + 1; y < ROOF - 2; y++) {
                Cell c = new Cell(x,y,z); if (mineable(c)) result.add(c);
            }
        return result;
    }
    static String shell(Cell c) {
        if (Math.abs(c.x) == RADIUS || Math.abs(c.z) == RADIUS || c.y == FLOOR || c.y == ROOF) return "BEDROCK";
        if (c.y == FLOOR + 1 && hub(c) && c.x == 0 && c.z == -21) return "LODESTONE";
        if (c.y == FLOOR + 4 && route(c) && Math.floorMod(c.x + c.z, 7) == 0) return "SEA_LANTERN";
        return air(c) ? "AIR" : "STONE";
    }
    /** Each emitted vein is face-connected and has at least three blocks. No isolated sprinkles. */
    static Map<Cell, String> veins(Collection<Cell> eligible, RandomGenerator random, double fraction) {
        var available = new HashSet<>(eligible);
        var seeds = new ArrayList<>(eligible);
        Collections.shuffle(seeds, new Random(random.nextLong()));
        var result = new LinkedHashMap<Cell, String>();
        int target = (int) (eligible.size() * Math.clamp(fraction, .05, .5));
        for (Cell seed : seeds) {
            if (result.size() >= target) break;
            if (!available.contains(seed)) continue;
            int size = random.nextInt(5, 12);
            var vein = new LinkedHashSet<Cell>();
            var frontier = new ArrayList<Cell>(); frontier.add(seed);
            while (!frontier.isEmpty() && vein.size() < size) {
                Cell next = frontier.remove(random.nextInt(frontier.size()));
                if (!available.contains(next) || !vein.add(next)) continue;
                for (Cell neighbor : next.neighbors()) if (available.contains(neighbor) && !vein.contains(neighbor)) frontier.add(neighbor);
            }
            if (vein.size() < 3) continue;
            String ore = MiningRules.randomOre(random).block();
            for (Cell c : vein) { available.remove(c); result.put(c, ore); }
        }
        return result;
    }
    static Map<Cell, String> refill(Map<Cell, String> current, Predicate<Cell> occupied, RandomGenerator random) {
        var next = new LinkedHashMap<>(current);
        var stone = new ArrayList<Cell>();
        current.forEach((c, material) -> {
            if (!occupied.test(c) && (material.equals("AIR") || material.equals("STONE"))) {
                next.put(c, "STONE"); stone.add(c);
            }
        });
        next.putAll(veins(stone, random, .22));
        return next;
    }
}
