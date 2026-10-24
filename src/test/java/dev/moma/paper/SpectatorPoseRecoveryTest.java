package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpectatorPoseRecoveryTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<SessionTools> tools;
    private MockedConstruction<EntityAdapter> entities;
    private final Map<UUID,Player> online=new HashMap<>();
    private final Map<UUID,Boolean> invisible=new HashMap<>();
    private final World world=mock(World.class);
    private GameService games;
    private Player owner,viewer,normal;

    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);tools=mockConstruction(SessionTools.class);
        entities=mockConstruction(EntityAdapter.class,(entity,c)->when(entity.advanceAll(any(),any())).thenReturn(true));
        var boards=mock(ScoreboardManager.class);bukkit.when(Bukkit::getScoreboardManager).thenReturn(boards);
        when(boards.getNewScoreboard()).thenAnswer(c->{var board=mock(Scoreboard.class);when(board.registerNewTeam(anyString())).thenReturn(mock(Team.class));return board;});
        bukkit.when(()->Bukkit.getPlayer(any(UUID.class))).thenAnswer(c->online.get(c.getArgument(0)));
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(Chunk.class));
        var maps=mock(ArenaMaps.class);when(maps.get("a")).thenReturn(new ArenaMap("a",world,0,64,0,new Grid(6)));
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        games=new GameService(plugin,maps,CampaignRules.standard(),mock(Lobby.class));
        owner=player("owner");viewer=player("viewer");normal=player("normal");
        games.join(owner,"a");games.spectate(viewer,games.session(owner).sessionId);
    }
    @AfterEach void cleanup(){entities.close();tools.close();bukkit.close();}
    private Player player(String name) {
        var player=mock(Player.class);UUID id=UUID.randomUUID();online.put(id,player);invisible.put(id,false);
        when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn(name);
        when(player.getScoreboard()).thenReturn(mock(Scoreboard.class));when(player.isCollidable()).thenReturn(true);
        when(player.isInvisible()).thenAnswer(c->invisible.get(id));
        doAnswer(c->{invisible.put(id,c.getArgument(0));return null;}).when(player).setInvisible(anyBoolean());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(player.teleport(any(Location.class))).thenReturn(true);return player;
    }
    private void poseCleanup(Player player){invisible.put(player.getUniqueId(),false);}

    @Test void nextTickRepairsPoseCleanupForCurrentObserversOnlyAndDoesNotRepeatWrites() {
        assertTrue(viewer.isInvisible());poseCleanup(viewer);poseCleanup(owner);poseCleanup(normal);
        clearInvocations(viewer,owner,normal);games.tick();assertTrue(viewer.isInvisible());
        verify(viewer).setInvisible(true);verify(owner,never()).setInvisible(anyBoolean());verify(normal,never()).setInvisible(anyBoolean());
        clearInvocations(viewer);games.tick();verify(viewer,never()).setInvisible(anyBoolean());
        verify(viewer,never()).setScoreboard(any());
        bukkit.verify(Bukkit::getPluginManager,never()); // Works without loading GSit or any optional API.
    }
    @Test void poseEndingAfterSpectatorLeavesNeverHidesThePlayerAgain() {
        games.leave(viewer);assertFalse(games.watching(viewer));poseCleanup(viewer);clearInvocations(viewer);
        games.tick();assertFalse(viewer.isInvisible());verify(viewer,never()).setInvisible(anyBoolean());
    }
    @Test void endedSessionIsRemovedBeforeAppearanceRecovery() {
        games.session(owner).arena.finish(Arena.Outcome.VICTORY);poseCleanup(viewer);clearInvocations(viewer);
        games.tick();assertFalse(games.watching(viewer));assertFalse(viewer.isInvisible());verify(viewer,never()).setInvisible(true);
    }
    @Test void staleWatchAndClosedGenerationCannotRestoreInvisibility() {
        online.remove(owner.getUniqueId());poseCleanup(viewer);games.tick(); // Removes the missing owner's session and releases its observers.
        assertFalse(games.watching(viewer));poseCleanup(viewer);clearInvocations(viewer);games.tick();verify(viewer,never()).setInvisible(true);
        var replacementOwner=player("replacement");games.join(replacementOwner,"a");games.spectate(viewer,games.session(replacementOwner).sessionId);
        games.suspendPresentation();poseCleanup(viewer);clearInvocations(viewer);
        games.tick();verify(viewer,never()).setInvisible(true);assertFalse(viewer.isInvisible());
    }
}
