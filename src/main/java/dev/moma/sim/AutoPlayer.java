package dev.moma.sim;

import dev.moma.core.*;
import java.util.*;
import java.util.function.Supplier;

/** Observable-state-only policy: no future rolls, enemy health scaling, or seed inspection. */
public final class AutoPlayer {
    public static final int TRANSACTION_INTERVAL = 2;
    public enum Strategy { BALANCED, AUTO_PLACE }
    private final HashRandom random;
    private final Strategy strategy;
    private final Arena.Spawner spawner;
    private final java.util.function.Consumer<UUID> remove;
    private final double[][] coverage;
    private final int[] rarities = new int[Rarity.values().length];
    private int summons, sales, moves;
    private final int primordialCap;

    public AutoPlayer(long seed, Strategy strategy, Grid grid, Supplier<UUID> ids) {
        this(seed, strategy, grid, ids, Integer.MAX_VALUE);
    }
    public AutoPlayer(long seed, Strategy strategy, Grid grid, Supplier<UUID> ids, int primordialCap) {
        this(seed, strategy, grid, (type, rarity, cell) -> ids.get(), id -> {}, primordialCap);
    }
    public AutoPlayer(long seed, Strategy strategy, Grid grid, Arena.Spawner spawner, java.util.function.Consumer<UUID> remove, int primordialCap) {
        if (primordialCap < 0) throw new IllegalArgumentException("Negative primordial cap");
        this.primordialCap = primordialCap;
        random = new HashRandom(seed); this.strategy = strategy; this.spawner = spawner; this.remove = remove;
        coverage = new double[UnitType.values().length * Rarity.values().length][grid.size() * grid.size()];
        for (UnitType type : UnitType.values()) for (Rarity rarity : Rarity.values()) {
            double range = type.profile().at(rarity).range();
            for (Cell cell : grid.placementOrder()) {
                int covered = 0;
                for (int i = 0; i < 144; i++) if (cell.point().distanceSquared(grid.route().at(i * grid.route().length() / 144)) <= range * range) covered++;
                coverage[index(type, rarity)][cell.row() * grid.size() + cell.column()] = covered / 144.0;
            }
        }
    }
    public int summons() { return summons; }
    public int sales() { return sales; }
    public int moves() { return moves; }
    public int[] rarities() { return rarities.clone(); }
    private int index(UnitType type, Rarity rarity) { return type.ordinal() * Rarity.values().length + rarity.ordinal(); }
    private double coverage(Defender d, Cell cell, Grid grid) { return coverage[index(d.type(), d.rarity())][cell.row() * grid.size() + cell.column()]; }
    private double score(Defender d) {
        double best = Arrays.stream(coverage[index(d.type(), d.rarity())]).max().orElse(0);
        CombatProfile p = d.profile();
        double targets = switch (d.type().role()) {
            case MELEE_SINGLE -> 1 + 0.25 * d.rarity().abilityLevel();
            case RANGED_SINGLE -> 1 + 0.12 * d.rarity().abilityLevel();
            case MELEE_CLEAVE, SMALL_AREA -> 2.5;
            case LARGE_AREA -> 4 + d.rarity().abilityLevel();
            case MULTI_TARGET -> p.targets() + d.rarity().abilityLevel();
        };
        return p.damage() * 20 / p.intervalTicks() * best * targets;
    }
    /** Up to ten transactions per second at 1x, below the live GUI's twenty; moves use legal empty cells. */
    public void act(Arena arena, long tick) {
        if (arena.ended() || tick % TRANSACTION_INTERVAL != 0) return;
        List<Defender> units = arena.defenders();
        if (units.size() == arena.grid().size() * arena.grid().size()) {
            var worst = units.stream().filter(d -> d.rarity().salePrice().isPresent()
                    && arena.coins() + d.rarity().salePrice().getAsInt() >= Arena.SUMMON_COST).min(Comparator.comparingDouble(this::score));
            if (worst.isPresent()) { sell(arena, worst.orElseThrow()); return; }
        }
        if (strategy == Strategy.BALANCED && tick % 40 == 0 && improvePlacement(arena, units)) return;
        if (arena.coins() >= Arena.SUMMON_COST && units.size() < arena.grid().size() * arena.grid().size()) {
            SummonRoll roll = SummonRoll.draw(random);
            // Stress-test intervention only: spend the same draw but downgrade excess Primordials.
            if (roll.rarity() == Rarity.PRIMORDIAL && rarities[Rarity.PRIMORDIAL.ordinal()] >= primordialCap)
                roll = new SummonRoll(roll.type(), Rarity.MYTHIC);
            if (arena.summon(arena.owner(), roll, spawner) == Arena.Result.OK) {
                summons++; rarities[roll.rarity().ordinal()]++;
            }
        }
    }
    private void sell(Arena arena, Defender d) {
        arena.select(arena.owner(), d.entityId());
        if (arena.sellSelected(arena.owner()) == Arena.Result.OK) { sales++; remove.accept(d.entityId()); }
    }
    private boolean improvePlacement(Arena arena, List<Defender> units) {
        Set<Cell> occupied = new HashSet<>(); for (Defender d : units) occupied.add(d.cell());
        Defender best = null; Cell destination = null; double improvement = 0.01;
        for (Defender d : units) for (Cell cell : arena.grid().placementOrder()) {
            if (occupied.contains(cell)) continue;
            double gain = (coverage(d, cell, arena.grid()) - coverage(d, d.cell(), arena.grid())) * d.profile().damage() / d.profile().intervalTicks();
            if (gain > improvement) { best = d; destination = cell; improvement = gain; }
        }
        if (best == null) return false;
        arena.select(arena.owner(), best.entityId());
        if (arena.moveSelected(arena.owner(), destination) != Arena.Result.OK) throw new IllegalStateException("Illegal bot move");
        moves++; return true;
    }
}
