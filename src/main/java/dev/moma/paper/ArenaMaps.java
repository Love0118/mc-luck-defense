package dev.moma.paper;

import dev.moma.core.Grid;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;
import java.io.*;
import java.util.*;

final class ArenaMaps {
    private final MomaPlugin plugin;
    private final Map<String, ArenaMap> maps = new LinkedHashMap<>();
    private final File file;
    private World world;

    ArenaMaps(MomaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "arenas.yml");
    }
    void load() {
        if (!file.exists()) return;
        var yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            maps.put(id, new ArenaMap(id, world(), yaml.getInt(id + ".x"), 64, yaml.getInt(id + ".z"), new Grid(yaml.getInt(id + ".size"))));
        }
    }
    private World world() {
        if (world != null) return world;
        world = Bukkit.createWorld(new WorldCreator("moma_arenas").generator(new ChunkGenerator() {
            @Override public boolean shouldGenerateNoise() { return false; }
            @Override public boolean shouldGenerateSurface() { return false; }
            @Override public boolean shouldGenerateCaves() { return false; }
            @Override public boolean shouldGenerateDecorations() { return false; }
            @Override public boolean shouldGenerateMobs() { return false; }
            @Override public boolean shouldGenerateStructures() { return false; }
        }));
        Objects.requireNonNull(world, "Could not create arena world");
        world.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);
        return world;
    }
    Collection<ArenaMap> all() { return Collections.unmodifiableCollection(maps.values()); }
    ArenaMap get(String id) { return maps.get(id); }
    boolean contains(Location location) { return maps.values().stream().anyMatch(m -> m.contains(location)); }
    ArenaMap create(String id, int size) throws IOException {
        if (!id.matches("[a-z0-9_-]{1,24}")) throw new IllegalArgumentException("전장 이름은 영문 소문자·숫자·_·- 1~24자입니다.");
        if (maps.containsKey(id)) throw new IllegalArgumentException("이미 있는 전장입니다.");
        int slot = maps.size();
        ArenaMap map = new ArenaMap(id, world(), slot * 128, 64, 0, new Grid(size));
        // Never overwrite blocks, including when a different plugin created this world.
        for (int x = -6; x <= map.maxOffset(); x++) {
            for (int z = -6; z <= map.maxOffset(); z++) {
                for (int y = 64; y <= 74; y++) {
                    if (!map.world().getBlockAt(map.originX() + x, y, z).isEmpty())
                        throw new IllegalArgumentException("생성 영역에 기존 블록이 있어 전장을 만들 수 없습니다.");
                }
            }
        }
        var yaml = new YamlConfiguration();
        for (ArenaMap existing : maps.values()) write(yaml, existing);
        write(yaml, map);
        yaml.save(file);
        maps.put(id, map);
        map.build();
        return map;
    }
    private void write(YamlConfiguration yaml, ArenaMap map) {
        yaml.set(map.id() + ".x", map.originX()); yaml.set(map.id() + ".z", map.originZ()); yaml.set(map.id() + ".size", map.grid().size());
    }
}
