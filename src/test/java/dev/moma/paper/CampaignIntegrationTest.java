package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampaignIntegrationTest {
    @Test void joiningCreatesTheSameCampaignRulesUsedByTheSimulator() {
        Player player = mock(Player.class); World world = mock(World.class);
        UUID owner = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getLocation()).thenReturn(new Location(world, 10, 70, 10));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        CampaignRules rules = CampaignRules.standard();
        GameSession session = new GameSession(player, new ArenaMap("a", world, 0, 64, 0, new Grid(5)), rules);
        assertEquals(rules.startingCoins(), session.arena.coins());
        assertEquals(rules.enemyLimit(), session.arena.enemyLimit());
        assertEquals(0, session.campaign.round());
        for (int tick = 0; tick <= rules.preparationTicks(); tick++) session.campaign.beforeCombat(session.arena, e -> UUID.randomUUID());
        assertEquals(1, session.campaign.round());
        assertEquals(WaveSchedule.create(rules).getFirst().entries().getFirst().enemy().health(), session.arena.enemies().getFirst().health());
    }
    @Test void renamedCommandRoutesJoinAndKeepsAdminGate() {
        ArenaMaps maps = mock(ArenaMaps.class); GameService games = mock(GameService.class);
        MomaCommand command = new MomaCommand(maps, games, CampaignRules.standard());
        Player player = mock(Player.class); when(player.hasPermission("moma.play")).thenReturn(true);
        assertTrue(command.onCommand(player, null, "mud", new String[]{"join", "arena1"}));
        verify(games).join(player, "arena1");
        command.onCommand(player, null, "mud", new String[]{"coins", "100"});
        verify(games, never()).session(player);
        CommandSender console = mock(CommandSender.class); when(console.hasPermission(anyString())).thenReturn(true);
        when(maps.all()).thenReturn(java.util.List.of());
        assertTrue(command.onCommand(console, null, "mud", new String[]{"list"}));
    }
}
