package dev.moma.core;

import java.util.ArrayList;
import java.util.List;

public final class Grid {
    public static final int SPACING = 3;
    private final int size;
    private final List<Cell> placementOrder;
    private final List<Cell> rangedPlacementOrder;
    private final Route route;

    public Grid(int size) {
        if (size < 2 || size > 15) throw new IllegalArgumentException("Grid size must be 2..15");
        this.size = size;
        this.route = new Route(-3, (size - 1) * SPACING + 3);
        var cells = new ArrayList<Cell>();
        for (int low = 0, high = size - 1; low <= high; low++, high--) {
            for (int x = low; x <= high; x++) cells.add(new Cell(x, low));
            for (int z = low + 1; z <= high; z++) cells.add(new Cell(high, z));
            if (high > low) {
                for (int x = high - 1; x >= low; x--) cells.add(new Cell(x, high));
                for (int z = high - 1; z > low; z--) cells.add(new Cell(low, z));
            }
        }
        placementOrder = List.copyOf(cells);
        var ranged = new ArrayList<Cell>();
        for (Cell cell : cells) if (!perimeter(cell)) ranged.add(cell);
        for (Cell cell : cells) if (perimeter(cell)) ranged.add(cell);
        rangedPlacementOrder = List.copyOf(ranged);
    }
    public int size() { return size; }
    public List<Cell> placementOrder() { return placementOrder; }
    public List<Cell> placementOrder(AttackRole role) { return role.melee() ? placementOrder : rangedPlacementOrder; }
    public boolean perimeter(Cell cell) {
        return contains(cell) && (cell.column() == 0 || cell.row() == 0 || cell.column() == size - 1 || cell.row() == size - 1);
    }
    public boolean contains(Cell cell) {
        return cell != null && cell.column() >= 0 && cell.column() < size && cell.row() >= 0 && cell.row() < size;
    }
    public Route route() { return route; }
}
