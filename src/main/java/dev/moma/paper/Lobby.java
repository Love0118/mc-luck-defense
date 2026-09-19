package dev.moma.paper;

import java.io.File;
import java.util.Objects;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

/** Dedicated lobby world. Its build is imported offline; startup never pastes over it. */
final class Lobby {
    private final Location spawn;
    private final double halfSize;

    Lobby(Location spawn, double halfSize) { this.spawn = spawn.clone(); this.halfSize = halfSize; }
    static Lobby load(MomaPlugin plugin) {
        var file = new File(plugin.getDataFolder(), "lobby.yml");
        if (!file.exists()) plugin.saveResource("lobby.yml", false);
        var config = YamlConfiguration.loadConfiguration(file);
        if (!config.getBoolean("enabled")) return null;
        String name = config.getString("world", "mud_lobby");
        if (!name.matches("[a-z0-9_-]{1,32}") || name.equals("moma_arenas")) throw new IllegalArgumentException("Invalid lobby world name");
        World world = Objects.requireNonNull(Bukkit.createWorld(new WorldCreator(name).generator(new ChunkGenerator() {
            @Override public boolean shouldGenerateNoise() { return false; }
            @Override public boolean shouldGenerateSurface() { return false; }
            @Override public boolean shouldGenerateCaves() { return false; }
            @Override public boolean shouldGenerateDecorations() { return false; }
            @Override public boolean shouldGenerateMobs() { return false; }
            @Override public boolean shouldGenerateStructures() { return false; }
        })));
        Location spawn = new Location(world, config.getDouble("spawn.x"), config.getDouble("spawn.y"),
                config.getDouble("spawn.z"), (float) config.getDouble("spawn.yaw"), (float) config.getDouble("spawn.pitch"));
        spawn.checkFinite();
        double half = config.getDouble("half-size", 256);
        if (!Double.isFinite(half) || half < 8 || half > 10000 || Math.abs(spawn.getX()) >= half || Math.abs(spawn.getZ()) >= half)
            throw new IllegalArgumentException("Lobby spawn is outside bounds");
        if (!world.getBlockAt(spawn.clone().subtract(0, 1, 0)).getType().isSolid()
                || !world.getBlockAt(spawn).isPassable() || !world.getBlockAt(spawn.clone().add(0, 1, 0)).isPassable())
            throw new IllegalStateException("Lobby spawn has no safe floor/headroom; import the schematic before enabling lobby.yml");
        world.setSpawnLocation(spawn);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setTime(6000); world.setStorm(false); world.setThundering(false);
        return new Lobby(spawn, half);
    }
    Location spawn() { return spawn.clone(); }
    boolean contains(Location location) { return spawn.getWorld().equals(location.getWorld()); }
    boolean outside(Location location) {
        return location.getY() < 1 || Math.abs(location.getX()) >= halfSize || Math.abs(location.getZ()) >= halfSize;
    }
    void prepare(Player player) {
        player.closeInventory();
        player.setFallDistance(0); player.setFireTicks(0); player.setFoodLevel(20);
        player.setGameMode(GameMode.ADVENTURE);
        player.setFlying(false); player.setAllowFlight(false);
    }
    void send(Player player) {
        prepare(player);
        if (!player.teleport(spawn())) throw new IllegalStateException("Lobby teleport rejected for " + player.getUniqueId());
    }
}
