package dev.moma.paper;

import dev.moma.core.Arena;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

final class GameSession {
    final Arena arena;
    final ArenaMap map;
    final Location returnLocation;
    final GameMode returnMode;
    final List<Chunk> tickets = new ArrayList<>();
    boolean defeatShown;

    GameSession(Player player, ArenaMap map, DevelopmentSettings settings) {
        this.map = map;
        arena = new Arena(map.id(), player.getUniqueId(), map.grid(), settings.startingCoins(), settings.enemyLimit());
        returnLocation = player.getLocation().clone(); returnMode = player.getGameMode();
    }
}
