package dev.moma.paper;

import dev.noqui.api.*;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiIntegrationTest {
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
        var respawn=mock(PlayerRespawnEvent.class);when(respawn.getPlayer()).thenReturn(player);when(games.quizParticipant(player)).thenReturn(true);listener.respawn(respawn);
        verify(games,never()).disconnect(player);verifyNoInteractions(lobby);
    }
    @Test void optionalAdapterLooksUpCurrentQuizAndReleasesItsRuntimeProvider() {
        var services=mock(ServicesManager.class);var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var menu=mock(LobbyMenu.class);var lobby=mock(Lobby.class);
        var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);
        var first=mock(QuizService.class);var replacement=mock(QuizService.class);when(first.participant(id)).thenReturn(true);when(replacement.participant(id)).thenReturn(false);
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
}
