package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionExitTest {
    private org.mockito.MockedConstruction<SessionTools> tools;
    private org.mockito.MockedConstruction<SpectatorAppearance> appearance;
    private World world;
    private GameService games;
    private ArenaMaps maps;
    private Lobby lobby;
    private Player owner;
    @BeforeEach void setup() {
        tools=mockConstruction(SessionTools.class);appearance=mockConstruction(SpectatorAppearance.class);
        world=mock(World.class);maps=mock(ArenaMaps.class);lobby=mock(Lobby.class);
        var map=new ArenaMap("exit",world,0,64,0,new Grid(6));
        when(maps.all()).thenReturn(List.of(map));when(maps.get("exit")).thenReturn(map);
        when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call->mock(Chunk.class));
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        games=new GameService(plugin,maps,CampaignRules.standard(),lobby);
        owner=player();games.start(owner);
        games.bgm=mock(BgmService.class);
    }
    @AfterEach void closeMocks(){appearance.close();tools.close();}
    private Player player() {
        Player player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(player.teleport(any(Location.class))).thenReturn(true);
        var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        return player;
    }
    private void round(int round)throws Exception {
        var session=games.session(owner);var elapsed=Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);
        var rules=CampaignRules.standard();
        elapsed.setLong(session.campaign,rules.preparationTicks()+(long)(round-1)*rules.roundTicks()-1);
        session.campaign.beforeCombat(session.arena,spawn->UUID.randomUUID());
        assertEquals(round,session.campaign.round());
    }
    private void request(Player player) {
        try(var bukkit=mockStatic(Bukkit.class)){games.requestLeave(player);}
    }
    @Test void optInAllowsRound99AndOffAllowsLateRounds()throws Exception {
        assertFalse(SessionExitPreferences.enabled(owner));
        SessionExitPreferences.toggle(owner);round(99);request(owner);
        assertFalse(games.playing(owner));verify(lobby).send(owner);
        games.start(owner);SessionExitPreferences.toggle(owner);round(500);request(owner);
        assertFalse(games.playing(owner));verify(lobby,times(2)).send(owner);
    }
    @Test void round100AndLaterRefuseExitWithoutChangingGameOrPresentation()throws Exception {
        SessionExitPreferences.toggle(owner);
        for(int round:new int[]{100,101,500}) {
            round(round);GameSession session=games.session(owner);
            double coins=session.arena.coins();int enemies=session.arena.enemyCount();long elapsed=session.campaign.elapsed();
            request(owner);request(owner);
            assertSame(session,games.session(owner));assertFalse(games.available("exit"));
            assertEquals(coins,session.arena.coins());assertEquals(enemies,session.arena.enemyCount());
            assertEquals(elapsed,session.campaign.elapsed());
        }
        verifyNoInteractions(games.bgm,lobby);verify(games.tools,never()).restore(owner);
        verify(owner,times(6)).playSound(any(Location.class),eq(Ui.Cue.ERROR.sound),eq(SoundCategory.MASTER),eq(Ui.Cue.ERROR.volume),eq(Ui.Cue.ERROR.pitch));
        SessionExitPreferences.toggle(owner);request(owner);
        assertFalse(games.playing(owner));verify(games.bgm).stop(owner);verify(lobby).send(owner);
    }
    @Test void commandsAndBedCannotBypassEnabledLock()throws Exception {
        SessionExitPreferences.toggle(owner);round(100);
        var commands=new MomaCommand(maps,games,CampaignRules.standard());
        commands.onCommand(owner,null,"mud",new String[]{"leave"});
        commands.onCommand(owner,null,"mud",new String[]{"lobby"});
        assertTrue(games.playing(owner));
        when(games.tools.holding(owner,"leave")).thenReturn(true);
        var listener=new GameListener(games,maps,mock(ShopMenu.class));
        var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(owner);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);listener.interact(event);listener.interact(event);
        }
        assertTrue(games.playing(owner));verify(event,times(2)).setCancelled(true);
        verifyNoInteractions(games.bgm,lobby);
        verify(owner,times(3)).playSound(any(Location.class),eq(Ui.Cue.ERROR.sound),eq(SoundCategory.MASTER),eq(Ui.Cue.ERROR.volume),eq(Ui.Cue.ERROR.pitch));
    }
    @Test void spectatorCanLeaveLateGameWithTheirOwnLockEnabled()throws Exception {
        round(500);Player viewer=player();SessionExitPreferences.toggle(viewer);
        games.spectate(viewer,games.session(owner).sessionId);assertTrue(games.watching(viewer));
        request(viewer);assertFalse(games.watching(viewer));assertTrue(games.playing(owner));verify(lobby).send(viewer);
    }
    @Test void endedGameAndInternalCleanupAreNotLocked()throws Exception {
        SessionExitPreferences.toggle(owner);round(100);games.session(owner).arena.finish(Arena.Outcome.ENEMY_LIMIT);
        request(owner);assertFalse(games.playing(owner));
        games.start(owner);round(100);
        try(var bukkit=mockStatic(Bukkit.class)){games.disconnect(owner);}
        assertFalse(games.playing(owner));
        games.start(owner);round(100);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(owner.getUniqueId())).thenReturn(owner);games.shutdown();
        }
        assertFalse(games.playing(owner));
    }
}
