package dev.moma.core;

public record Point(double x, double z) {
    public double distanceSquared(Point other) {
        double dx = x - other.x, dz = z - other.z;
        return dx * dx + dz * dz;
    }
}
