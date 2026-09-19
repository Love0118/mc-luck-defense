package dev.moma.paper;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class SpectatorAppearanceTest {
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
