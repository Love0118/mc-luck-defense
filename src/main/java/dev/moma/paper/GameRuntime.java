package dev.moma.paper;

import dev.moma.bootstrap.GameModule;
import dev.moma.core.CampaignRules;
import dev.moma.runtime.StateCodec;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;

/** Replaceable generation. Suspending unbinds services but never releases a game session. */
public final class GameRuntime implements GameModule {
    private MomaPlugin host;
    private GameService games;
    private ArenaMaps maps;
    private Lobby lobby;
    private CampaignRules settings;
    private MomaCommand command;
    private BgmService.Saved savedBgm;
    private String bgmConfiguration;
    private boolean active;
    public GameRuntime() {}
    @Override public void prepare(MomaPlugin host,byte[] snapshot)throws Exception {
        this.host=host;settings=CampaignRules.standard();maps=new ArenaMaps(host);maps.load();lobby=Lobby.load(host);
        games=new GameService(host,maps,settings,lobby);
        bgmConfiguration=BgmService.prepareConfiguration(host);
        if(snapshot!=null){SessionState.Game saved=StateCodec.read(snapshot,SessionState.Game.class);games.restoreState(saved);savedBgm=saved.bgm();}
    }
    @Override public void activate(boolean initialBoot)throws Exception {
        if(active)return;
        games.achievements=new AchievementService(host);
        if(lobby!=null)try{games.leaderboard=new RoundLeaderboard(host,lobby);}
        catch(Exception error){host.getLogger().log(java.util.logging.Level.SEVERE,"Leaderboard initialization failed",error);}
        try{games.bgm=new BgmService(host,games,bgmConfiguration);games.bgm.restoreState(savedBgm);}
        catch(Exception error){if(savedBgm!=null)throw error;host.getLogger().log(java.util.logging.Level.SEVERE,"BGM initialization failed",error);}
        if(initialBoot)for(var player:Bukkit.getOnlinePlayers())games.tools.restore(player);
        games.entities.enablePresentationMetadata(host);
        var events=Bukkit.getPluginManager();
        events.registerEvents(new UnsignedChat(host),host);
        var shop=new ShopMenu(host,games);var menu=new LobbyMenu(host,games);
        events.registerEvents(menu,host);events.registerEvents(new TraitMenu(games,lobby),host);events.registerEvents(new SpectatorListener(games),host);
        if(lobby!=null) {
            events.registerEvents(new LobbyListener(lobby,games,menu),host);
            var portals=new LobbyPortals(host,lobby,games,menu);events.registerEvents(portals,host);
            Bukkit.getScheduler().runTaskTimer(host,portals,1,5);
            if(initialBoot)for(var player:Bukkit.getOnlinePlayers())lobby.send(player);
        }
        events.registerEvents(shop,host);events.registerEvents(new GameListener(games,maps,shop),host);
        if(initialBoot) {
            for(var world:Bukkit.getWorlds())for(Entity entity:world.getEntities())if(games.entities.managed(entity))entity.remove();
        } else games.rebindPresentation();
        command=new MomaCommand(maps,games,settings,menu);
        Bukkit.getScheduler().runTaskTimer(host,games::tick,1,1);
        Bukkit.getScheduler().runTaskTimer(host,shop::refreshOpen,5,5);
        Bukkit.getScheduler().runTaskTimer(host,menu::refreshOpen,20,20);
        Bukkit.getScheduler().runTaskTimer(host,TabStatus::update,20,20);
        active=true;
    }
    @Override public void checkReloadReady(){if(games.bgm!=null)games.bgm.checkReloadReady();}
    @Override public byte[] snapshot()throws Exception {checkReloadReady();return StateCodec.write(games.saveState());}
    @Override public String fingerprint(){return StateCodec.fingerprint(games.saveState().combatOnly());}
    @Override public boolean hasSessions(){return games.hasSessions();}
    @Override public Object diagnosticState(){return games;}
    @Override public void suspend()throws Exception {
        active=false;
        try{if(games.bgm!=null){savedBgm=games.bgm.saveState();games.bgm.suspend();}}
        finally{detach();}
    }
    @Override public void discard() {
        active=false;
        try{if(games.bgm!=null)games.bgm.close();}
        finally{detach();}
    }
    private void detach() {
        for(var player:Bukkit.getOnlinePlayers()) {
            var holder=player.getOpenInventory().getTopInventory().getHolder();
            if(holder!=null && holder.getClass().getClassLoader()==getClass().getClassLoader())player.closeInventory();
        }
        Bukkit.getScheduler().cancelTasks(host);HandlerList.unregisterAll(host);
        games.suspendPresentation();
        if(games.leaderboard!=null){games.leaderboard.close();games.leaderboard=null;}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){return this.command.onCommand(sender,command,label,args);}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){return this.command.onTabComplete(sender,command,alias,args);}
    @Override public void shutdown() {
        if(games==null)return;active=false;
        Bukkit.getScheduler().cancelTasks(host);HandlerList.unregisterAll(host);
        if(games.bgm!=null)games.bgm.close();games.shutdown();
        if(games.leaderboard!=null)games.leaderboard.close();
        for(var player:Bukkit.getOnlinePlayers())games.tools.restore(player);
    }
}
