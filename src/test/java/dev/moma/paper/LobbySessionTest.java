package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LobbySessionTest {
    private Player player(World world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world, 0, 65, 0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(player.teleport(any(Location.class))).thenReturn(true);
        return player;
    }
    private ArenaMaps maps(World world) {
        ArenaMaps maps = mock(ArenaMaps.class);
        var a = new ArenaMap("a",world,0,64,0,new Grid(6));
        var b = new ArenaMap("b",world,128,64,0,new Grid(6));
        when(maps.all()).thenReturn(List.of(a,b)); when(maps.get("a")).thenReturn(a); when(maps.get("b")).thenReturn(b);
        when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call -> mock(Chunk.class));
        return maps;
    }
    private MomaPlugin plugin() {
        var plugin=mock(MomaPlugin.class); when(plugin.namespace()).thenReturn("momadefense"); return plugin;
    }
    @Test void simultaneousStartsAreExclusiveAndFreedSlotsCreateFreshSessions() {
        World world=mock(World.class); Lobby lobby=mock(Lobby.class);
        GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
        Player first=player(world), second=player(world), third=player(world);
        games.start(first); GameSession previous=games.session(first);
        assertThrows(IllegalArgumentException.class,()->games.start(first));
        assertThrows(IllegalArgumentException.class,()->games.join(second,"a"));
        games.start(second);
        assertEquals("a",games.session(first).arena.id()); assertEquals("b",games.session(second).arena.id());
        assertNotSame(games.session(first).arena,games.session(second).arena);
        assertThrows(IllegalArgumentException.class,()->games.start(third));
        games.session(first).arena.credit(777);
        games.leave(first); verify(lobby).send(first);
        assertTrue(games.playing(second));
        games.start(third);
        assertEquals("a",games.session(third).arena.id()); assertNotSame(previous,games.session(third));
        assertEquals(CampaignRules.standard().startingCoins(),games.session(third).arena.coins());
        assertEquals(0,games.session(third).campaign.round());
    }
    @Test void everyTerminalOutcomeReleasesEntitiesTicketsAndReturnsOnce() {
        for (Arena.Outcome outcome : List.of(Arena.Outcome.ENEMY_LIMIT,Arena.Outcome.TIME_LIMIT,Arena.Outcome.VICTORY)) {
            World world=mock(World.class); Lobby lobby=mock(Lobby.class);
            GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
            Player player=player(world); games.start(player); GameSession session=games.session(player);
            UUID defender=UUID.randomUUID(), enemy=UUID.randomUUID();
            session.arena.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->defender);
            session.arena.addEnemy(new Enemy(enemy,"a",EnemyType.ZOMBIE,100,1,1,false));
            session.arena.finish(outcome);
            var defenderEntity=mock(org.bukkit.entity.Entity.class); var enemyEntity=mock(org.bukkit.entity.Entity.class);
            try (var bukkit=mockStatic(Bukkit.class)) {
                bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
                bukkit.when(()->Bukkit.getEntity(defender)).thenReturn(defenderEntity);
                bukkit.when(()->Bukkit.getEntity(enemy)).thenReturn(enemyEntity);
                games.tick(); games.tick();
            }
            assertFalse(games.playing(player)); assertTrue(games.available("a"));
            verify(lobby,times(1)).send(player); verify(defenderEntity).remove(); verify(enemyEntity).remove();
            for (Chunk chunk:session.tickets) verify(chunk).removePluginChunkTicket(any());
            verify(player,times(2)).sendMessage(any(Component.class)); // Entry + result.
        }
    }
    @Test void disconnectFreesSessionWithoutTeleportAndFailedEntryDoesNotReserveArena() {
        World world=mock(World.class); Lobby lobby=mock(Lobby.class);
        GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
        Player player=player(world); games.start(player); games.disconnect(player); games.disconnect(player);
        assertFalse(games.playing(player)); assertTrue(games.available("a")); verifyNoInteractions(lobby);
        when(player.teleport(any(Location.class))).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->games.start(player));
        assertFalse(games.playing(player)); assertTrue(games.available("a"));
    }
    @Test void lobbyJoinRespawnAndFallRecoveryNeverCreateACombatSession() {
        World world=mock(World.class); Lobby lobby=new Lobby(new Location(world,-5.5,54,4.5),256);
        GameService games=mock(GameService.class); LobbyMenu menu=mock(LobbyMenu.class);
        LobbyListener listener=new LobbyListener(lobby,games,menu); Player player=player(world);
        PlayerJoinEvent join=mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(player); listener.join(join);
        verify(player).teleport(lobby.spawn()); verify(games,never()).start(any());
        var move=mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(player); when(move.getTo()).thenReturn(new Location(world,0,-2,0));
        listener.move(move); verify(move).setTo(lobby.spawn());
        var respawn=mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(player);
        listener.respawn(respawn); verify(games).disconnect(player); verify(respawn).setRespawnLocation(lobby.spawn());
    }
    @Test void startMenuRejectsShiftBottomAndRepeatedClicksAndDefersEntry() {
        var plugin=plugin(); GameService games=mock(GameService.class); World world=mock(World.class);
        LobbyMenu menu=new LobbyMenu(plugin,games,maps(world)); Player player=player(world);
        when(player.hasPermission("moma.play")).thenReturn(true); when(player.isOnline()).thenReturn(true);
        var inventory=mock(org.bukkit.inventory.Inventory.class); var view=mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        var action=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        try (var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(()->Bukkit.createInventory(any(org.bukkit.inventory.InventoryHolder.class),eq(27),any(Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
            try (var items=mockConstruction(org.bukkit.inventory.ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) { menu.open(player); }
            var event=mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(22); when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.SHIFT_LEFT);
            menu.click(event); verifyNoInteractions(scheduler);
            when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT); when(event.getRawSlot()).thenReturn(49);
            menu.click(event); verifyNoInteractions(scheduler);
            when(event.getRawSlot()).thenReturn(22); menu.click(event); menu.click(event);
            verify(scheduler,times(1)).runTask(eq(plugin),action.capture()); verify(games,never()).start(any());
            action.getValue().run(); verify(games,times(1)).start(player);
            verify(event,times(4)).setCancelled(true);
        }
    }
}
