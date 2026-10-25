package dev.moma.paper;

import dev.noqui.api.TicketService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/** Refreshes the active minigame's quiz icon synchronously after a ticket grant. */
final class QuizTicketInventory implements TicketService.Inventory {
    private final GameService games;
    private final MiningBridge mine;
    private QuizTicketInventory(GameService games,MiningBridge mine){this.games=games;this.mine=mine;}
    static void register(MomaPlugin plugin,GameService games,MiningBridge mine){
        unregister(games);
        Bukkit.getServicesManager().register(TicketService.Inventory.class,new QuizTicketInventory(games,mine),plugin,ServicePriority.Normal);
    }
    static void unregister(GameService games){
        var services=Bukkit.getServicesManager();
        for(var registration:services.getRegistrations(TicketService.Inventory.class))
            if(registration.getProvider() instanceof QuizTicketInventory inventory&&inventory.games==games)
                services.unregister(TicketService.Inventory.class,inventory);
    }
    @Override public boolean ownsInventory(Player player){
        return games.mining!=null&&games.mining.owns(player)||games.active(player)||games.canReturnToNoqui(player);
    }
    @Override public void refreshTickets(Player player,long balance){
        if(games.mining!=null&&games.mining.owns(player))mine.refresh(player);
        else games.refreshNoquiTool(player);
    }
}
