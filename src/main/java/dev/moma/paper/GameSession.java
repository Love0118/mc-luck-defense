package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

final class GameSession {
    final UUID sessionId = UUID.randomUUID();
    final Arena arena;
    final ArenaMap map;
    final Location returnLocation;
    final GameMode returnMode;
    final boolean returnAllowFlight, returnFlying;
    final List<Chunk> tickets = new ArrayList<>();
    final Campaign campaign;
    final HashRandom random = HashRandom.secure();
    final AttackEffects attackEffects = new AttackEffects();
    boolean assisted;
    int announcedRound;
    long simulationTick;
    private int speed = 1;

    int speed() { return speed; }
    void speed(int value) {
        if (value != 1 && value != 2 && value != 4 && value != 8)
            throw new IllegalArgumentException("배속은 1, 2, 4, 8 중에서 선택하세요.");
        speed = value;
    }

    GameSession(Player player, ArenaMap map, CampaignRules settings) {
        this.map = map;
        arena = new Arena(map.id(), player.getUniqueId(), map.grid(), settings.startingCoins(), settings.enemyLimit());
        campaign = new Campaign(settings);
        returnLocation = player.getLocation().clone(); returnMode = player.getGameMode();
        returnAllowFlight = player.getAllowFlight(); returnFlying = player.isFlying();
    }
}
