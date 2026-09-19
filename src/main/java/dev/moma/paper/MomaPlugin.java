package dev.moma.paper;

import dev.moma.core.CampaignRules;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

public final class MomaPlugin extends JavaPlugin {
    private GameService games;
    // Keep existing entity data keys valid across the plugin rename.
    @Override public String namespace() { return "momadefense"; }
    @Override public void onEnable() {
        CampaignRules settings = CampaignRules.standard();
        var maps = new ArenaMaps(this);
        maps.load();
        Lobby lobby = Lobby.load(this);
        games = new GameService(this, maps, settings, lobby);
        var shop = new ShopMenu(this, games);
        var lobbyMenu = new LobbyMenu(this, games);
        getServer().getPluginManager().registerEvents(lobbyMenu, this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(games), this);
        if (lobby != null) {
            getServer().getPluginManager().registerEvents(new LobbyListener(lobby, games, lobbyMenu), this);
            for (var player : Bukkit.getOnlinePlayers()) lobby.send(player);
        }
        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(new GameListener(games, maps, shop), this);
        for (var world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (games.entities.managed(entity)) entity.remove();
        var command = new MomaCommand(maps, games, settings, lobbyMenu);
        Objects.requireNonNull(getCommand("mud")).setExecutor(command);
        Objects.requireNonNull(getCommand("mud")).setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, games::tick, 1, 1);
        getServer().getScheduler().runTaskTimer(this, TabStatus::update, 20, 20);
        getLogger().info("MC Luck Defense enabled. Paper 26.3.build.19-alpha; /mud; 100-round campaign.");
    }
    @Override public void onDisable() {
        if (games != null) games.shutdown();
    }
}
