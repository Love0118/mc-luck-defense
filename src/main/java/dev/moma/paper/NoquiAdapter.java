package dev.moma.paper;

import dev.noqui.api.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/** Only loaded when Noqui is enabled; each invocation resolves the current provider. */
final class NoquiAdapter implements NoquiHooks {
    private final DefenseService defense;
    NoquiAdapter(MomaPlugin plugin,GameService games,Lobby lobby,LobbyMenu menu) {
        defense=new DefenseService() {
            @Override public boolean active(Player p){return games.active(p);}
            @Override public void startGame(Player p){games.start(p);}
            @Override public void releaseLobbyTools(Player p){if(!games.active(p))games.tools.restore(p);}
            @Override public void silenceBgm(Player p){if(games.bgm!=null)games.bgm.stop(p);}
            @Override public void sendLobby(Player p){if(!games.active(p) && lobby!=null){lobby.send(p);games.refreshNoquiTool(p);}}
            @Override public void openMenu(Player p){if(!games.active(p))menu.open(p);}
        };
        Bukkit.getServicesManager().register(DefenseService.class,defense,plugin,ServicePriority.Normal);
    }
    private QuizService quiz(){return Bukkit.getServicesManager().load(QuizService.class);}
    @Override public boolean started(){var service=quiz();return service!=null && service.started();}
    @Override public boolean owns(Player p){var service=quiz();return service!=null && service.ownsCabin(p.getUniqueId());}
    @Override public boolean participant(Player p){var service=quiz();return service!=null && service.participant(p.getUniqueId());}
    @Override public void openControls(Player p){var service=quiz();if(service!=null)service.openControls(p);}
    @Override public void prepareDefense(Player p){var service=quiz();if(service!=null)service.prepareDefense(p);}
    @Override public void defenseEntered(Player p){var service=quiz();if(service!=null)service.defenseEntered(p);}
    @Override public void defenseFailed(Player p){var service=quiz();if(service!=null)service.defenseFailed(p);}
    @Override public boolean rewardStarlight(java.util.UUID player,String transactionId,long amount){
        var service=Bukkit.getServicesManager().load(LobbyService.class);if(service==null)return false;
        service.rewardStarlight(player,transactionId,amount);return true;
    }
    @Override public boolean routeJoin(Player p){var service=quiz();return service!=null && service.routeJoin(p);}
    @Override public void leave(Player p){var service=quiz();if(service!=null && service.participant(p.getUniqueId()))service.leave(p);}
    @Override public void close(){Bukkit.getServicesManager().unregister(DefenseService.class,defense);}
}
