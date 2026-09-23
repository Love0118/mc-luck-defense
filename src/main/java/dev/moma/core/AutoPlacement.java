package dev.moma.core;

import java.util.*;

/** Maximizes base DPS weighted by the fraction of the looping route in range. */
public final class AutoPlacement {
    private static final int SAMPLES = 336;
    private final Grid grid;
    private final Map<Double, double[]> coverage = new HashMap<>();

    public AutoPlacement(Grid grid) { this.grid = grid; }

    double score(Defender defender, int cellIndex) {
        CombatProfile profile = defender.profile();
        double[] fractions = coverage.computeIfAbsent(profile.range(), range -> {
            double[] result = new double[grid.placementOrder().size()];
            for (int c = 0; c < result.length; c++) {
                Point point = grid.placementOrder().get(c).point();
                for (int sample = 0; sample < SAMPLES; sample++)
                    if (point.distanceSquared(grid.route().at(sample * grid.route().length() / SAMPLES)) <= range * range)
                        result[c] += 1.0 / SAMPLES;
            }
            return result;
        });
        return fractions[cellIndex] * profile.damage() / profile.intervalTicks();
    }

    public Map<UUID, Cell> arrange(List<Defender> units) {
        int m=grid.placementOrder().size();
        units=units.stream().sorted(Comparator.comparingInt((Defender d)->d.rarity().ordinal()).reversed()
                .thenComparing(Comparator.comparingDouble((Defender d)->d.profile().damage()/d.profile().intervalTicks()).reversed()))
                .limit(m).toList();
        int n=units.size();
        double[][] cost = new double[n][m];
        double maximum = 1;
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            cost[i][j] = -score(units.get(i), j);
            maximum = Math.max(maximum, -cost[i][j]);
        }
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            Cell cell = grid.placementOrder().get(j);
            // Equal-coverage ties keep ranged units inside and avoid unnecessary moves.
            cost[i][j] = cost[i][j] / maximum
                    + (units.get(i).type().role().melee() == grid.perimeter(cell) ? 0 : 1e-10)
                    + (Objects.equals(units.get(i).cell(),cell) ? 0 : 1e-12);
        }
        // Rectangular Hungarian assignment: each defender gets exactly one distinct cell.
        double[] u = new double[n + 1], v = new double[m + 1];
        int[] owner = new int[m + 1], previous = new int[m + 1];
        for (int row = 1; row <= n; row++) {
            owner[0] = row;
            int column = 0;
            double[] best = new double[m + 1]; Arrays.fill(best, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
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
        return result;
    }
}
