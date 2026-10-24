package dev.moma.paper;

import dev.noqui.api.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/** Only loaded when Noqui is enabled; each invocation resolves the current provider. */
final class NoquiAdapter implements NoquiHooks {
    private final DefenseService defense;
    NoquiAdapter(MomaPlugin plugin,GameService games,Lobby lobby,MinigameMenu menu,SettingsMenu settings,TraitMenu traits) {
        defense=new DefenseService() {
            @Override public boolean active(Player p){return games.active(p)||games.mining!=null&&games.mining.owns(p);}
            @Override public void startGame(Player p){games.start(p);}
            @Override public void releaseLobbyTools(Player p){if(!games.active(p))games.tools.restore(p);}
            @Override public void silenceBgm(Player p){if(games.bgm!=null)games.bgm.stop(p);}
            @Override public void sendLobby(Player p){if(!games.active(p) && lobby!=null){lobby.send(p);games.refreshNoquiTool(p);}}
            @Override public void openMenu(Player p){if(!games.playing(p))menu.open(p);}
            @Override public void openSettings(Player p){settings.open(p);}
            @Override public void openTraits(Player p){traits.open(p);}
            @Override public void leaveGame(Player p){if(games.mining!=null&&games.mining.owns(p))games.mining.leave(p,true);else games.requestLeave(p);}
        };
        Bukkit.getServicesManager().register(DefenseService.class,defense,plugin,ServicePriority.Normal);
    }
    private QuizService quiz(){return Bukkit.getServicesManager().load(QuizService.class);}
    @Override public boolean started(){var service=quiz();return service!=null && service.started();}
    @Override public boolean owns(Player p){var service=quiz();return service!=null && service.ownsCabin(p.getUniqueId());}
    @Override public boolean participant(Player p){var service=quiz();return service!=null && service.participant(p.getUniqueId());}
    @Override public void openControls(Player p){var service=quiz();if(service!=null)service.openControls(p);}
    @Override public void openWardrobe(Player p){var service=Bukkit.getServicesManager().load(LobbyService.class);if(service!=null)service.openWardrobe(p);}
    private LobbyService lobbyService(){var service=Bukkit.getServicesManager().load(LobbyService.class);if(service==null)throw new IllegalStateException("별빛 지갑이 준비되지 않았습니다.");return service;}
    @Override public long starlight(java.util.UUID player){return lobbyService().starlight(player);}
    @Override public boolean spendStarlight(java.util.UUID player,String transaction,long amount){return lobbyService().spendStarlight(player,transaction,amount);}
    @Override public void openSharedShop(Player p){lobbyService().openShop(p);}
    @Override public void openMiningShop(Player p,Runnable sales,Runnable upgrade){lobbyService().openMiningShop(p,sales,upgrade);}
    @Override public void prepareItems(Player p){lobbyService().prepareInventoryChange(p);}
    @Override public void openProxy(Player p){var service=quiz();if(service!=null)service.openProxy(p);}
    @Override public long ticketCount(java.util.UUID player){var service=quiz();return service==null?0:service.ticketCount(player);}
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
