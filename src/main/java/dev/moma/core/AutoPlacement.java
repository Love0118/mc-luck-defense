package dev.moma.core;

import java.util.*;

/** Maximizes base DPS weighted by the fraction of the looping route in range. */
public final class AutoPlacement {
    private static final int SAMPLES = 336;
    private record CoverageKey(int size,double range) {}
    private static final Map<CoverageKey,double[]> SHARED_COVERAGE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Comparator<Defender> PRIORITY=(a,b)-> {
        int grade=Integer.compare(b.rarity().ordinal(),a.rarity().ordinal());
        return grade!=0?grade:Double.compare(b.profile().damage()/b.profile().intervalTicks(),a.profile().damage()/a.profile().intervalTicks());
    };
    private final Grid grid;
    private final Map<Double, double[]> coverage = new HashMap<>();
    private final double[][] cost;
    private final double[] u,v,best;
    private final int[] owner,previous;
    private final boolean[] used,perimeter;
    private final UUID[] lastIds;
    private final CombatProfile[] lastProfiles;
    private final Cell[] lastCells;
    private int lastSize=-1;
    private Map<UUID,Cell> lastLayout;

    public AutoPlacement(Grid grid) {
        this.grid=grid;int m=grid.placementOrder().size();
        cost=new double[m][m];u=new double[m+1];v=new double[m+1];best=new double[m+1];
        owner=new int[m+1];previous=new int[m+1];used=new boolean[m+1];perimeter=new boolean[m];
        lastIds=new UUID[m];lastProfiles=new CombatProfile[m];lastCells=new Cell[m];
        for(int j=0;j<m;j++)perimeter[j]=grid.perimeter(grid.placementOrder().get(j));
    }

    double score(Defender defender, int cellIndex) {
        CombatProfile profile = defender.profile();
        return fractions(profile.range())[cellIndex] * profile.damage() / profile.intervalTicks();
    }
    private double[] fractions(double range) {
        return coverage.computeIfAbsent(range,value -> SHARED_COVERAGE.computeIfAbsent(new CoverageKey(grid.size(),value),key -> {
            double[] result = new double[grid.placementOrder().size()];
            for (int c = 0; c < result.length; c++) {
                Point point = grid.placementOrder().get(c).point();
                for (int sample = 0; sample < SAMPLES; sample++)
                    if (point.distanceSquared(grid.route().at(sample * grid.route().length() / SAMPLES)) <= range * range)
                        result[c] += 1.0 / SAMPLES;
            }
            return result;
        }));
    }

    public Map<UUID, Cell> arrange(List<Defender> units) {
        int m=grid.placementOrder().size();
        units=units.stream().sorted(PRIORITY).limit(m).toList();
        int n=units.size();
        boolean same=n==lastSize;
        for(int i=0;same && i<n;i++) {
            Defender d=units.get(i);
            same=d.entityId().equals(lastIds[i]) && d.profile().equals(lastProfiles[i]) && Objects.equals(d.cell(),lastCells[i]);
        }
        if(same)return new LinkedHashMap<>(lastLayout);
        lastSize=n;
        for(int i=0;i<n;i++){Defender d=units.get(i);lastIds[i]=d.entityId();lastProfiles[i]=d.profile();lastCells[i]=d.cell();}
        double maximum = 1;
        for (int i = 0; i < n; i++) {
            CombatProfile profile=units.get(i).profile();double[] fractions=fractions(profile.range());
            for (int j = 0; j < m; j++) {
                cost[i][j] = -(fractions[j]*profile.damage()/profile.intervalTicks());
                maximum = Math.max(maximum, -cost[i][j]);
            }
        }
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            Cell cell = grid.placementOrder().get(j);
            // Equal-coverage ties keep ranged units inside and avoid unnecessary moves.
            cost[i][j] = cost[i][j] / maximum
                    + (units.get(i).type().role().melee() == perimeter[j] ? 0 : 1e-10)
                    + (Objects.equals(units.get(i).cell(),cell) ? 0 : 1e-12);
        }
        // Rectangular Hungarian assignment: each defender gets exactly one distinct cell.
        Arrays.fill(u,0);Arrays.fill(v,0);Arrays.fill(owner,0);Arrays.fill(previous,0);
        for (int row = 1; row <= n; row++) {
            owner[0] = row;
            int column = 0;
            Arrays.fill(best,Double.POSITIVE_INFINITY);Arrays.fill(used,false);
            do {
                used[column] = true;
                int current = owner[column], next = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= m; j++) if (!used[j]) {
                    double reduced = cost[current - 1][j - 1] - u[current] - v[j];
                    if (reduced < best[j]) { best[j] = reduced; previous[j] = column; }
                    if (best[j] < delta) { delta = best[j]; next = j; }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) { u[owner[j]] += delta; v[j] -= delta; }
                    else best[j] -= delta;
                }
                column = next;
            } while (owner[column] != 0);
            do {
                int next = previous[column]; owner[column] = owner[next]; column = next;
            } while (column != 0);
        }
        Map<UUID, Cell> result = new LinkedHashMap<>();
        for (int j = 1; j <= m; j++) if (owner[j] != 0)
            result.put(units.get(owner[j] - 1).entityId(), grid.placementOrder().get(j - 1));
        lastLayout=new LinkedHashMap<>(result);
        return result;
    }
}
