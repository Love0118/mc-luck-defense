package dev.moma.paper;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SpectatorAppearanceTest {
    @Test void lostPoseInvisibilityIsReappliedWithoutReplacingTheSavedReturnState() {
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("viewer");
        Scoreboard old=mock(Scoreboard.class),board=mock(Scoreboard.class);Team team=mock(Team.class);
        when(player.getScoreboard()).thenReturn(old);when(player.isCollidable()).thenReturn(true);
        boolean[] invisible={false};when(player.isInvisible()).thenAnswer(c->invisible[0]);
        doAnswer(c->{invisible[0]=c.getArgument(0);return null;}).when(player).setInvisible(anyBoolean());
        ScoreboardManager manager=mock(ScoreboardManager.class);when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewTeam(anyString())).thenReturn(team);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);var appearance=new SpectatorAppearance();
            appearance.enter(player,true);assertTrue(player.isInvisible());clearInvocations(player,team);
            appearance.refreshObserver(player);verify(player,never()).setInvisible(anyBoolean());
            invisible[0]=false;appearance.refreshObserver(player);appearance.refreshObserver(player);
            assertTrue(player.isInvisible());verify(player).setInvisible(true);
            verify(player,never()).setScoreboard(any());verifyNoInteractions(team);
            appearance.leave(player);assertFalse(player.isInvisible());verify(player).setCollidable(true);verify(player).setScoreboard(old);
            clearInvocations(player);appearance.refreshObserver(player);verify(player,never()).setInvisible(anyBoolean());
            invisible[0]=true;appearance.enter(player,true);invisible[0]=false;appearance.refreshObserver(player);
            appearance.close();assertTrue(player.isInvisible()); // Originally invisible players keep their return state.
            invisible[0]=false;clearInvocations(player);appearance.refreshObserver(player);verify(player,never()).setInvisible(anyBoolean());
        }
    }
    @Test void refreshDoesNotUseAFormerPlayerObjectWithTheSameIdentity() {
        var original=mock(Player.class);var replacement=mock(Player.class);UUID id=UUID.randomUUID();
        when(original.getUniqueId()).thenReturn(id);when(replacement.getUniqueId()).thenReturn(id);
        var manager=mock(ScoreboardManager.class);var board=mock(Scoreboard.class);var team=mock(Team.class);
        when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewTeam(anyString())).thenReturn(team);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);var appearance=new SpectatorAppearance();
            appearance.enter(original,true);appearance.refreshObserver(replacement);verify(replacement,never()).setInvisible(anyBoolean());
        }
    }
    @Test void translucentTeamAndPriorStateAreRestoredAfterSwitches() {
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("viewer");
        Scoreboard old=mock(Scoreboard.class),board=mock(Scoreboard.class);Team team=mock(Team.class);
        when(player.getScoreboard()).thenReturn(old);when(player.isCollidable()).thenReturn(true);
        ScoreboardManager manager=mock(ScoreboardManager.class);when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewTeam(anyString())).thenReturn(team);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            var appearance=new SpectatorAppearance();appearance.enter(player,true);appearance.enter(player,true);
            verify(team).setCanSeeFriendlyInvisibles(true);verify(team).setOption(Team.Option.COLLISION_RULE,Team.OptionStatus.NEVER);
            verify(player,times(2)).setInvisible(true);
            appearance.leave(player);appearance.leave(player);
            verify(player).setInvisible(false);verify(player).setCollidable(true);verify(player).setScoreboard(old);
        }
    }
}
