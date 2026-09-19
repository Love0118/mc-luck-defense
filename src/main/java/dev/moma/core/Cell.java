package dev.moma.core;

public record Cell(int column, int row) {
    public Point point() { return new Point(column * Grid.SPACING, row * Grid.SPACING); }
}
