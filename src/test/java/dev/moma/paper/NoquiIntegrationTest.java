package dev.moma.paper;

import dev.noqui.api.*;
import java.util.UUID;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiIntegrationTest {
    private Player at(org.bukkit.World world) {
        var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getLocation()).thenReturn(new org.bukkit.Location(world,0,65,0));
        when(p.getGameMode()).thenReturn(org.bukkit.GameMode.ADVENTURE);when(p.teleport(any(org.bukkit.Location.class))).thenReturn(true);return p;
    }
    @Test void lobbyIconRoutesWithoutCombatAndStoppedQuizCannotTravel() {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var lobby=mock(Lobby.class);var p=at(mock(org.bukkit.World.class));when(lobby.contains(p.getLocation())).thenReturn(true);
        boolean[] started={true};int[] routes={0};
        try(var tools=mockConstruction(SessionTools.class)) {
            var games=new GameService(plugin,mock(ArenaMaps.class),dev.moma.core.CampaignRules.standard(),lobby);
            games.noqui=new NoquiHooks(){public boolean started(){return started[0];}public boolean routeJoin(Player player){routes[0]++;return true;}};
            games.requestNoquiReturn(p);assertEquals(1,routes[0]);assertFalse(games.active(p));verify(lobby,never()).send(p);
            started[0]=false;games.requestNoquiReturn(p);assertEquals(1,routes[0]);verify(games.tools).updateNoqui(p,false,false);
        }
    }
    @Test void iconTracksQuizStateInLobbyDefenseAndSpectatorsButDoesNotTouchQuizOrOtherGames() {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var lobby=mock(Lobby.class);var maps=mock(ArenaMaps.class);
        var arenaWorld=mock(org.bukkit.World.class);var lobbyWorld=mock(org.bukkit.World.class);var otherWorld=mock(org.bukkit.World.class);
        var idle=at(lobbyWorld);var owner=at(arenaWorld);var viewer=at(arenaWorld);var other=at(otherWorld);var quiz=at(otherWorld);
        when(lobby.contains(idle.getLocation())).thenReturn(true);when(maps.get("a")).thenReturn(new ArenaMap("a",arenaWorld,0,64,0,new dev.moma.core.Grid(6)));
        when(arenaWorld.getChunkAt(anyInt(),anyInt())).thenReturn(mock(org.bukkit.Chunk.class));boolean[] started={true};
        try(var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class);var bukkit=mockStatic(Bukkit.class)) {
            var games=new GameService(plugin,maps,dev.moma.core.CampaignRules.standard(),lobby);
            games.noqui=new NoquiHooks(){public boolean started(){return started[0];}public boolean owns(Player p){return p==quiz;}};
            games.join(owner,"a");verify(games.tools).updateNoqui(owner,true,true);
            games.spectate(viewer,games.session(owner).sessionId);verify(games.tools).updateNoqui(viewer,true,false);
            clearInvocations(games.tools);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(idle,owner,viewer,other,quiz));games.refreshNoquiTools();
            verify(games.tools).updateNoqui(idle,true,false);verify(games.tools).updateNoqui(owner,true,true);verify(games.tools).updateNoqui(viewer,true,false);
            verify(games.tools).updateNoqui(other,false,false);verify(games.tools,never()).updateNoqui(eq(quiz),anyBoolean(),anyBoolean());
            clearInvocations(games.tools);started[0]=false;games.refreshNoquiTools();
            verify(games.tools).updateNoqui(idle,false,false);verify(games.tools).updateNoqui(owner,false,true);verify(games.tools).updateNoqui(viewer,false,false);
            assertFalse(games.canReturnToNoqui(idle));games.noqui=NoquiHooks.NONE;assertFalse(games.canReturnToNoqui(owner));
        }
    }
    @Test void returnLookupUsesNewProvidersAndMissingServiceHidesIcon() {
        var services=mock(ServicesManager.class);var current=new java.util.concurrent.atomic.AtomicReference<QuizService>();
        when(services.load(QuizService.class)).thenAnswer(c->current.get());var active=mock(QuizService.class);when(active.started()).thenReturn(true);
        var stopped=mock(QuizService.class);var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var lobby=mock(Lobby.class);var p=at(mock(org.bukkit.World.class));
        when(lobby.contains(p.getLocation())).thenReturn(true);
        try(var tools=mockConstruction(SessionTools.class);var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);var games=new GameService(plugin,mock(ArenaMaps.class),dev.moma.core.CampaignRules.standard(),lobby);
            games.noqui=new NoquiAdapter(plugin,games,lobby,mock(LobbyMenu.class));current.set(active);games.refreshNoquiTool(p);verify(games.tools).updateNoqui(p,true,false);
            current.set(stopped);games.refreshNoquiTool(p);verify(games.tools).updateNoqui(p,false,false);assertFalse(games.noqui.started());
            current.set(null);assertFalse(games.noqui.started());assertFalse(games.canReturnToNoqui(p));
        }
    }
    @Test void optionalHooksLoadAndRunWithNoNoquiApiClasses()throws Exception {
        var url=NoquiHooks.class.getProtectionDomain().getCodeSource().getLocation();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{url},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.startsWith("dev.noqui."))throw new ClassNotFoundException(name);
                if(name.startsWith("dev.moma.paper.NoquiHooks"))synchronized(getClassLoadingLock(name)) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Class<?> hooks=loader.loadClass("dev.moma.paper.NoquiHooks");var field=hooks.getDeclaredField("NONE");field.setAccessible(true);Object none=field.get(null);
            var started=hooks.getDeclaredMethod("started");started.setAccessible(true);assertEquals(false,started.invoke(none));
            var route=hooks.getDeclaredMethod("routeJoin",Player.class);route.setAccessible(true);assertEquals(false,route.invoke(none,new Object[]{null}));
            assertThrows(ClassNotFoundException.class,()->loader.loadClass("dev.noqui.api.QuizService"));
        }
    }
    @Test void defenseLeaveRoutesThroughActiveQuizInsteadOfReplacingItsTools() {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var lobby=mock(Lobby.class);
        var world=mock(org.bukkit.World.class);var maps=mock(ArenaMaps.class);var p=mock(Player.class);UUID id=UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);when(p.getLocation()).thenReturn(new org.bukkit.Location(world,0,65,0));when(p.getGameMode()).thenReturn(org.bukkit.GameMode.ADVENTURE);
        when(p.teleport(any(org.bukkit.Location.class))).thenReturn(true);when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(org.bukkit.Chunk.class));
        var arena=new ArenaMap("a",world,0,64,0,new dev.moma.core.Grid(6));when(maps.get("a")).thenReturn(arena);
        try(var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class)) {
            var games=new GameService(plugin,maps,dev.moma.core.CampaignRules.standard(),lobby);
            int[] routed={0};games.noqui=new NoquiHooks(){public boolean routeJoin(Player player){assertFalse(games.active(player));routed[0]++;return true;}};
            games.join(p,"a");games.leave(p);assertFalse(games.playing(p));verifyNoInteractions(lobby);
            var owner=mock(Player.class);when(owner.getUniqueId()).thenReturn(UUID.randomUUID());when(owner.getLocation()).thenReturn(new org.bukkit.Location(world,0,65,0));
            when(owner.getGameMode()).thenReturn(org.bukkit.GameMode.ADVENTURE);when(owner.teleport(any(org.bukkit.Location.class))).thenReturn(true);
            when(maps.get("b")).thenReturn(new ArenaMap("b",world,128,64,0,new dev.moma.core.Grid(6)));
            games.join(owner,"a");games.spectate(p,games.session(owner).sessionId);games.join(p,"b");
            assertTrue(games.playing(p));assertFalse(games.watching(p));assertEquals(1,routed[0]);verify(lobby).send(p);
        }
    }
    @Test void quizJoinAndRespawnDoNotTouchDefenseLobbyOrInventory() {
        var games=mock(GameService.class);var lobby=mock(Lobby.class);var menu=mock(LobbyMenu.class);var player=mock(Player.class);
        var listener=new LobbyListener(lobby,games,menu);var join=mock(PlayerJoinEvent.class);when(join.getPlayer()).thenReturn(player);
        when(games.routeNoquiJoin(player)).thenReturn(true);listener.join(join);verifyNoInteractions(lobby,menu);verify(player,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        var respawn=mock(PlayerRespawnEvent.class);when(respawn.getPlayer()).thenReturn(player);when(games.quizCabinOwner(player)).thenReturn(true);listener.respawn(respawn);
        verify(games,never()).disconnect(player);verifyNoInteractions(lobby);
    }
    @Test void optionalAdapterLooksUpCurrentQuizAndReleasesItsRuntimeProvider() {
        var services=mock(ServicesManager.class);var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var menu=mock(LobbyMenu.class);var lobby=mock(Lobby.class);
        var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var first=mock(QuizService.class);var replacement=mock(QuizService.class);when(first.participant(id)).thenReturn(true);when(first.ownsCabin(id)).thenReturn(true);when(replacement.ownsCabin(id)).thenReturn(false);
        when(services.load(QuizService.class)).thenReturn(first,replacement,first,first);
        try(var tools=mockConstruction(SessionTools.class);var bukkit=mockStatic(Bukkit.class)) {
            var games=spy(new GameService(plugin,mock(ArenaMaps.class),dev.moma.core.CampaignRules.standard()));games.bgm=mock(BgmService.class);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);var adapter=new NoquiAdapter(plugin,games,lobby,menu);
            assertTrue(adapter.owns(p));assertFalse(adapter.owns(p));adapter.leave(p);verify(first).leave(p);
            var provider=org.mockito.ArgumentCaptor.forClass(DefenseService.class);
            verify(services).register(eq(DefenseService.class),provider.capture(),eq(plugin),eq(ServicePriority.Normal));
            provider.getValue().releaseLobbyTools(p);provider.getValue().silenceBgm(p);provider.getValue().sendLobby(p);provider.getValue().openMenu(p);
            verify(games.tools).restore(p);verify(games.bgm).stop(p);verify(lobby).send(p);verify(menu).open(p);
            when(games.active(p)).thenReturn(true);provider.getValue().openMenu(p);verify(menu,times(1)).open(p);
            adapter.close();verify(services).unregister(DefenseService.class,provider.getValue());
        }
    }
    @Test void joinAndSpectateKeepMembershipAndReportPrepareSuccessAndFailure() {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var world=mock(org.bukkit.World.class);
        var maps=mock(ArenaMaps.class);when(maps.get("a")).thenReturn(new ArenaMap("a",world,0,64,0,new dev.moma.core.Grid(6)));
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(org.bukkit.Chunk.class));var owner=at(world);var viewer=at(world);
        try(var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class);var bukkit=mockStatic(Bukkit.class)) {
            var games=new GameService(plugin,maps,dev.moma.core.CampaignRules.standard());var hooks=mock(NoquiHooks.class);games.noqui=hooks;
            games.join(owner,"a");var order=inOrder(hooks,owner);order.verify(hooks).prepareDefense(owner);order.verify(owner).teleport(any(org.bukkit.Location.class));order.verify(hooks).defenseEntered(owner);
            games.spectate(viewer,games.session(owner).sessionId);verify(hooks).prepareDefense(viewer);verify(hooks).defenseEntered(viewer);verify(hooks,never()).leave(any());
            games.leave(viewer);games.leave(owner);verify(hooks,never()).leave(any());
            var failing=at(world);when(failing.teleport(any(org.bukkit.Location.class))).thenReturn(false);
            assertThrows(IllegalArgumentException.class,()->games.join(failing,"a"));assertFalse(games.active(failing));verify(hooks).defenseFailed(failing);verify(hooks,never()).defenseEntered(failing);
            assertTrue(games.available("a"));
        }
    }
    @Test void defenseRewardIsOnlyForOwnerAndOncePerSessionCleanup()throws Exception {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var world=mock(org.bukkit.World.class);var maps=mock(ArenaMaps.class);
        when(maps.get("a")).thenReturn(new ArenaMap("a",world,0,64,0,new dev.moma.core.Grid(6)));when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(org.bukkit.Chunk.class));
        var owner=at(world);var viewer=at(world);var calls=new java.util.ArrayList<String>();
        var rewardDir=java.nio.file.Files.createTempDirectory("moma-rewards-test-");
        try(var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class);var bukkit=mockStatic(Bukkit.class)) {
            var games=new GameService(plugin,maps,dev.moma.core.CampaignRules.standard());games.rewards=new DefenseRewards(rewardDir.resolve("rewards.json"),1,()->new NoquiHooks(){
                public boolean rewardStarlight(UUID id,String transaction,long amount){assertEquals(owner.getUniqueId(),id);assertEquals(42,amount);calls.add(transaction);return true;}
            },s->{});
            games.join(owner,"a");var session=games.session(owner);var elapsed=dev.moma.core.Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);var rules=dev.moma.core.CampaignRules.standard();
            elapsed.setLong(session.campaign,rules.preparationTicks()+41L*rules.roundTicks()-1);session.campaign.beforeCombat(session.arena,spawn->UUID.randomUUID());
            games.spectate(viewer,session.sessionId);games.leave(viewer);assertTrue(calls.isEmpty());games.leave(owner);games.leave(owner);assertEquals(List.of("moma:"+session.sessionId+":"+owner.getUniqueId()),calls);
        }
    }
}
