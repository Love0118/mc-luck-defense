package dev.moma.core;

import java.util.*;

/** Geometric interpolation between monotonically increasing round/health anchors. */
public record HealthCurve(List<Anchor> anchors) {
    public record Anchor(int round, double health) {}
    public HealthCurve {
        anchors = List.copyOf(anchors);
        if (anchors.size() < 2 || anchors.getFirst().round() != 1 || anchors.getLast().round() != 100)
            throw new IllegalArgumentException("Health curve must span rounds 1..100");
        int previousRound = 0; double previousHealth = 0;
        for (Anchor anchor : anchors) {
            if (anchor.round() <= previousRound || !Double.isFinite(anchor.health()) || anchor.health() <= 0 || anchor.health() < previousHealth)
                throw new IllegalArgumentException("Health anchors must increase in round and not decrease in health");
            previousRound = anchor.round(); previousHealth = anchor.health();
        }
    }
    public static HealthCurve parse(String specification) {
        var anchors = new ArrayList<Anchor>();
        for (String item : specification.split(",")) {
            String[] pair = item.trim().split(":");
            if (pair.length != 2) throw new IllegalArgumentException("Expected round:health");
            anchors.add(new Anchor(Integer.parseInt(pair[0]), Double.parseDouble(pair[1])));
        }
        return new HealthCurve(anchors);
    }
    public double at(int round) {
        if (round < 1 || round > 100) throw new IllegalArgumentException("Round must be 1..100");
        for (int i = 1; i < anchors.size(); i++) {
            Anchor end = anchors.get(i), start = anchors.get(i - 1);
            if (round <= end.round()) {
                double fraction = (round - start.round()) / (double) (end.round() - start.round());
                return start.health() * Math.pow(end.health() / start.health(), fraction);
            }
        }
        throw new IllegalStateException();
    }
    public String specification() {
        return String.join(",", anchors.stream().map(a -> a.round() + ":" + a.health()).toList());
    }
}
