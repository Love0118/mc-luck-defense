package dev.moma.core;

/** Clockwise square; cumulative progress is never reset at the start of a lap. */
public record Route(double min, double max) implements java.io.Serializable {
    public Route {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) throw new IllegalArgumentException("Invalid route");
    }
    public double length() { return 4 * (max - min); }
    public Point at(double progress) {
        double side = max - min;
        double d = ((progress % length()) + length()) % length();
        if (d < side) return new Point(min + d, min);
        if (d < 2 * side) return new Point(max, min + d - side);
        if (d < 3 * side) return new Point(max - (d - 2 * side), max);
        return new Point(min, max - (d - 3 * side));
    }
}
