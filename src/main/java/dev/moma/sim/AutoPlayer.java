package dev.moma.sim;

import dev.moma.core.*;
import java.util.*;
import java.util.function.Supplier;

/** Observable-state-only policy: no future rolls, enemy health scaling, or seed inspection. */
public final class AutoPlayer {
    public static final int TRANSACTION_INTERVAL = 1;
    public enum Strategy { BALANCED, AUTO_PLACE }
    private final HashRandom random;
    private final Arena.Spawner spawner;
    private final java.util.function.Consumer<UUID> remove;
    private static final Map<Integer,double[]> COVERAGE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int RARITY_COUNT=Rarity.values().length;
    private final double[] bestCoverage;
    private final int[] rarities = new int[RARITY_COUNT];
    private int summons, sales, moves;
    private final AutoPlacement placement;
    private final int primordialCap;

    public AutoPlayer(long seed, Strategy strategy, Grid grid, Supplier<UUID> ids) {
        this(seed, strategy, grid, ids, Integer.MAX_VALUE);
    }
    public AutoPlayer(long seed, Strategy strategy, Grid grid, Supplier<UUID> ids, int primordialCap) {
        this(seed, strategy, grid, (type, rarity, cell) -> ids.get(), id -> {}, primordialCap);
    }
    public AutoPlayer(long seed, Strategy strategy, Grid grid, Arena.Spawner spawner, java.util.function.Consumer<UUID> remove, int primordialCap) {
        if (primordialCap < 0) throw new IllegalArgumentException("Negative primordial cap");
        this.primordialCap = primordialCap;placement=new AutoPlacement(grid);
        random = new HashRandom(seed); this.spawner = spawner; this.remove = remove;
        bestCoverage=COVERAGE.computeIfAbsent(grid.size(),size->coverageFor(grid));
    }
    private static double[] coverageFor(Grid grid) {
        double[][] coverage = new double[UnitType.values().length * Rarity.values().length][grid.size() * grid.size()];
        for (UnitType type : UnitType.values()) for (Rarity rarity : Rarity.values()) {
            double range = type.profile().at(rarity).range();
            for (Cell cell : grid.placementOrder()) {
                int covered = 0;
                for (int i = 0; i < 144; i++) if (cell.point().distanceSquared(grid.route().at(i * grid.route().length() / 144)) <= range * range) covered++;
                coverage[index(type, rarity)][cell.row() * grid.size() + cell.column()] = covered / 144.0;
            }
        }
        double[] best=new double[coverage.length];
        for(int i=0;i<best.length;i++)best[i]=Arrays.stream(coverage[i]).max().orElse(0);
        return best;
    }
    public int summons() { return summons; }
    public int sales() { return sales; }
    public int moves() { return moves; }
    public int[] rarities() { return rarities.clone(); }
    private static int index(UnitType type, Rarity rarity) { return type.ordinal() * RARITY_COUNT + rarity.ordinal(); }
    private double score(Defender d) {
        double best = bestCoverage[index(d.type(), d.rarity())];
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
    /** Every game tick executes the same bounded purchase budget at any simulator batch size. */
    public void act(Arena arena,long tick) {
        if(arena.ended())return;
        if(arena.coins()<arena.summonCost() && arena.reserveCount()==0)return;
        boolean dirty=false;
        for(int action=0;action<4;action++) {
            if(arena.coins()<arena.summonCost() || arena.unitCount()>=arena.grid().size()*arena.grid().size()+Arena.RESERVE_CAPACITY) {
                Defender worst=null;double worstScore=Double.POSITIVE_INFINITY;
                for(Defender d:arena.reserveUnits())if(d.rarity().autoSellable()) {
                    double score=score(d);
                    if(worst==null || Double.compare(score,worstScore)<0){worst=d;worstScore=score;}
                }
                if(worst!=null){sell(arena,worst);dirty=true;continue;}
                break;
            }
            SummonRoll roll=SummonRoll.draw(random,arena);
            if(roll.rarity()==Rarity.PRIMORDIAL && rarities[Rarity.PRIMORDIAL.ordinal()]>=primordialCap)
                roll=new SummonRoll(roll.type(),Rarity.MYTHIC);
            if(arena.summon(arena.owner(),roll,spawner)!=Arena.Result.OK)break;
            summons++;rarities[roll.rarity().ordinal()]++;arena.collectMergedEntities().forEach(remove);dirty=true;
        }
        if(dirty) {
            var layout=placement.arrange(arena.units());
            for(Defender d:arena.units())if(!Objects.equals(d.cell(),layout.get(d.entityId())))moves++;
            arena.rearrange(arena.owner(),layout);
        }
    }
    private void sell(Arena arena, Defender d) {
        arena.select(arena.owner(), d.entityId());
        if (arena.sellSelected(arena.owner()) == Arena.Result.OK) { sales++; remove.accept(d.entityId()); }
    }
}
