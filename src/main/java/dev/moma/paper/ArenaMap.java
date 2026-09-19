package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;

record ArenaMap(String id, World world, int originX, int floorY, int originZ, Grid grid) {
    Location location(Point point) { return new Location(world, originX + point.x() + 0.5, floorY + 1, originZ + point.z() + 0.5); }
    Location entrance() { return location(new Point((grid.size() - 1) * 1.5, -5)); }
    int maxOffset() { return (grid.size() - 1) * Grid.SPACING + 6; }
    boolean contains(Location location) {
        return world.equals(location.getWorld()) && location.getX() >= originX - 6 && location.getX() < originX + maxOffset() + 1
                && location.getZ() >= originZ - 6 && location.getZ() < originZ + maxOffset() + 1;
    }
    Cell cellAt(Block block) {
        if (block == null || !world.equals(block.getWorld()) || block.getY() != floorY) return null;
        int x = block.getX() - originX, z = block.getZ() - originZ;
        if (x < 0 || z < 0 || x % Grid.SPACING != 0 || z % Grid.SPACING != 0) return null;
        Cell cell = new Cell(x / Grid.SPACING, z / Grid.SPACING);
        return grid.contains(cell) ? cell : null;
    }
    void build() {
        int end = (grid.size() - 1) * Grid.SPACING;
        for (int x = -6; x <= end + 6; x++) {
            for (int z = -6; z <= end + 6; z++) {
                Material material = Material.SMOOTH_STONE;
                if (((x == -3 || x == end + 3) && z >= -3 && z <= end + 3)
                        || ((z == -3 || z == end + 3) && x >= -3 && x <= end + 3)) material = Material.RED_CONCRETE;
                if (x >= 0 && x <= end && z >= 0 && z <= end && x % Grid.SPACING == 0 && z % Grid.SPACING == 0)
                    material = Material.LIGHT_BLUE_CONCRETE;
                world.getBlockAt(originX + x, floorY, originZ + z).setType(material, false);
                if (x == -6 || x == end + 6 || z == -6 || z == end + 6)
                    world.getBlockAt(originX + x, floorY + 1, originZ + z).setType(Material.GLASS, false);
            }
        }
    }
}
