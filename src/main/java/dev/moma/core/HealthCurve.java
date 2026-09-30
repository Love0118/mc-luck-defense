package dev.moma.core;

import java.util.*;

/** Geometric interpolation between monotonically increasing round/health anchors. */
public record HealthCurve(List<Anchor> anchors) implements java.io.Serializable {
    public record Anchor(int round, double health) implements java.io.Serializable {}
    public HealthCurve {
        anchors = List.copyOf(anchors);
        if (anchors.size() < 2 || anchors.getFirst().round() != 1 || anchors.getLast().round() < 100)
            throw new IllegalArgumentException("Health curve must span at least rounds 1..100");
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
        if (round < 1 || round > anchors.getLast().round()) throw new IllegalArgumentException("Round outside health curve");
        int low=1,high=anchors.size()-1;
        while(low<high) {
            int middle=(low+high)>>>1;
            if(anchors.get(middle).round()<round)low=middle+1;else high=middle;
        }
        Anchor end=anchors.get(low),start=anchors.get(low-1);
        double fraction=(round-start.round())/(double)(end.round()-start.round());
        return start.health()*Math.pow(end.health()/start.health(),fraction);
    }
    public String specification() {
        return String.join(",", anchors.stream().map(a -> a.round() + ":" + a.health()).toList());
    }
}
