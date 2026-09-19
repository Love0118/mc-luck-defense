package dev.moma.paper;

import org.bukkit.event.*;
import org.bukkit.event.player.*;

final class SpectatorListener implements Listener {
    private final GameService games;
    SpectatorListener(GameService games) { this.games = games; }
    @EventHandler(ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) {
        if (games.watching(event.getPlayer()) && !games.spectatorDestination(event.getPlayer(), event.getTo())) event.setCancelled(true);
    }
    @EventHandler public void mode(PlayerGameModeChangeEvent event) {
        if (games.watching(event.getPlayer()) && event.getNewGameMode() != org.bukkit.GameMode.SPECTATOR) event.setCancelled(true);
    }
}
