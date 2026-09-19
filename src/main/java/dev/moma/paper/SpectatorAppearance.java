package dev.moma.paper;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
/** Invisible teammates render translucently on unmodified clients. */
final class SpectatorAppearance {
    private record Saved(Player player, Scoreboard board, boolean invisible, boolean collidable) {}
    private final Map<UUID, Saved> saved = new HashMap<>();
    private Scoreboard board;
    private Team team;
    void enter(Player player, boolean observer) {
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            team = board.registerNewTeam("mud_viewers");
            team.setCanSeeFriendlyInvisibles(true);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        saved.computeIfAbsent(player.getUniqueId(), id -> new Saved(player, player.getScoreboard(), player.isInvisible(), player.isCollidable()));
        team.addEntry(player.getName()); player.setScoreboard(board);
        if (observer) { player.setInvisible(true); player.setCollidable(false); }
    }
    void leave(Player player) {
        Saved previous = saved.remove(player.getUniqueId());
        if (previous == null) return;
        team.removeEntry(player.getName());
        player.setInvisible(previous.invisible); player.setCollidable(previous.collidable);
        player.setScoreboard(previous.board);
    }
    void close() { for (Saved previous : List.copyOf(saved.values())) leave(previous.player); }
}
