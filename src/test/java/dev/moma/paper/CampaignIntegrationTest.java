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
    @Test void olderFiveByFiveArenaIsRejectedBeforeAnyPlayerStateChanges() {
        MomaPlugin plugin = mock(MomaPlugin.class); when(plugin.namespace()).thenReturn("momadefense");
        ArenaMaps maps = mock(ArenaMaps.class);
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(maps.get("old")).thenReturn(new ArenaMap("old", mock(World.class), 0, 64, 0, new Grid(5)));
        var games = new GameService(plugin, maps, CampaignRules.standard());
        var error = assertThrows(IllegalArgumentException.class, () -> games.join(player, "old"));
        assertTrue(error.getMessage().contains("6×6"));
        assertNull(games.session(player));
        verify(player, never()).teleport(any(Location.class));
        verify(player, never()).setGameMode(any());
    }
    @Test void sixBySixMapBuilds36CellsAndResolvesTheNewOuterBoundary() {
        World world = mock(World.class);
        var floor = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floor);
        when(floor.isEmpty()).thenReturn(true);
        var map = new ArenaMap("a", world, 128, 64, 0, new Grid(6));
        map.build();
        verify(floor, times(36)).setType(Material.LIGHT_BLUE_CONCRETE, false);
        verify(floor,times(48)).setType(Material.PURPLE_CONCRETE,false);
        assertEquals(84, map.grid().route().length());
        assertTrue(map.contains(new Location(world, 149.99, 65, 21.99)));
        assertFalse(map.contains(new Location(world, 150, 65, 0)));
        when(floor.getWorld()).thenReturn(world); when(floor.getY()).thenReturn(64);
        when(floor.getX()).thenReturn(143); when(floor.getZ()).thenReturn(15);
        assertEquals(new Cell(5, 5), map.cellAt(floor));
        when(floor.getX()).thenReturn(146); assertNull(map.cellAt(floor));
    }
    @Test void joiningCreatesTheSameCampaignRulesUsedByTheSimulator() {
        Player player = mock(Player.class); World world = mock(World.class);
        UUID owner = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getLocation()).thenReturn(new Location(world, 10, 70, 10));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        CampaignRules rules = CampaignRules.standard();
        GameSession session = new GameSession(player, new ArenaMap("a", world, 0, 64, 0, new Grid(rules.gridSize())), rules);
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
