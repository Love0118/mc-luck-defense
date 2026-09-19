package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

final class GameSession {
    final Arena arena;
    final ArenaMap map;
    final Location returnLocation;
    final GameMode returnMode;
    final List<Chunk> tickets = new ArrayList<>();
    final Campaign campaign;
    final HashRandom random = HashRandom.secure();
    final Set<UUID> hitEffects = new LinkedHashSet<>();
    boolean assisted;
    int announcedRound;

    GameSession(Player player, ArenaMap map, CampaignRules settings) {
        this.map = map;
        arena = new Arena(map.id(), player.getUniqueId(), map.grid(), settings.startingCoins(), settings.enemyLimit());
        campaign = new Campaign(settings);
        returnLocation = player.getLocation().clone(); returnMode = player.getGameMode();
    }
}
