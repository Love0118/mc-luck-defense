package dev.moma.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

public final class MomaPlugin extends JavaPlugin {
    private GameService games;
    @Override public void onEnable() {
        saveDefaultConfig();
        DevelopmentSettings settings = DevelopmentSettings.load(getConfig());
        var maps = new ArenaMaps(this);
        maps.load();
        games = new GameService(this, maps, settings);
        var shop = new ShopMenu(this, games);
        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(new GameListener(games, maps, shop), this);
        for (var world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (games.entities.managed(entity)) entity.remove();
        var command = new MomaCommand(maps, games, settings);
        Objects.requireNonNull(getCommand("moma")).setExecutor(command);
        Objects.requireNonNull(getCommand("moma")).setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, games::tick, 1, 1);
        getLogger().info("MomaDefense enabled. Paper 26.3.build.19-alpha; development balance, manual enemy spawning.");
    }
    @Override public void onDisable() {
        if (games != null) games.shutdown();
    }
}
