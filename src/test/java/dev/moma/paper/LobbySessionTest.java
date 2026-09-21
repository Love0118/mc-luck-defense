package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LobbySessionTest {
    private org.mockito.MockedConstruction<SpectatorAppearance> appearance;
    private org.mockito.MockedConstruction<SessionTools> tools;
    @BeforeEach void mockTools() { tools=mockConstruction(SessionTools.class); appearance=mockConstruction(SpectatorAppearance.class); }
    @AfterEach void closeTools() { tools.close(); appearance.close(); }
    private Player player(World world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world, 0, 65, 0));
        GameMode[] mode = {GameMode.ADVENTURE}; boolean[] flight = {false, false};
        when(player.getGameMode()).thenAnswer(call -> mode[0]);
        doAnswer(call -> { mode[0] = call.getArgument(0); return null; }).when(player).setGameMode(any());
        when(player.getAllowFlight()).thenAnswer(call -> flight[0]);
        when(player.isFlying()).thenAnswer(call -> flight[1]);
        doAnswer(call -> { flight[0] = call.getArgument(0); return null; }).when(player).setAllowFlight(anyBoolean());
        doAnswer(call -> { flight[1] = call.getArgument(0); assertFalse(flight[1] && !flight[0]); return null; }).when(player).setFlying(anyBoolean());
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
        games.session(first).arena.credit(777);
        games.leave(first); verify(lobby).send(first);
        assertTrue(games.playing(second));
        games.start(third);
        assertEquals("a",games.session(third).arena.id()); assertNotSame(previous,games.session(third));
        assertEquals(CampaignRules.standard().startingCoins(),games.session(third).arena.coins());
        assertEquals(0,games.session(third).campaign.round());
    }
    @Test void sessionAchievementCountsOnlySuccessfulStartsIncludingFreshRetries() {
        World world=mock(World.class);Lobby lobby=mock(Lobby.class);
        GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
        games.achievements=mock(AchievementService.class);Player player=player(world),viewer=player(world);
        when(player.teleport(any(Location.class))).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->games.start(player));
        verifyNoInteractions(games.achievements);
        when(player.teleport(any(Location.class))).thenReturn(true);
        games.start(player);verify(games.achievements).sessionStarted(player);
        assertThrows(IllegalArgumentException.class,()->games.start(player));
        games.spectate(viewer,games.session(player).sessionId);verify(games.achievements,never()).sessionStarted(viewer);
        try(var bukkit=mockStatic(Bukkit.class)){games.leave(player);}
        games.start(player);verify(games.achievements,times(2)).sessionStarted(player);
    }
    @Test void everyTerminalOutcomeReleasesEntitiesTicketsAndReturnsOnce() {
        for (Arena.Outcome outcome : List.of(Arena.Outcome.ENEMY_LIMIT,Arena.Outcome.TIME_LIMIT,Arena.Outcome.VICTORY)) {
            World world=mock(World.class); Lobby lobby=spy(new Lobby(new Location(world,0,65,0),256));
            GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
            Player player=player(world); games.start(player); GameSession session=games.session(player);
            assertTrue(player.getAllowFlight()); assertTrue(player.isFlying());
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
            assertFalse(player.getAllowFlight()); assertFalse(player.isFlying());
            verify(lobby,times(1)).send(player); verify(defenderEntity).remove(); verify(enemyEntity).remove();
            for (Chunk chunk:session.tickets) verify(chunk).removePluginChunkTicket(any());
            verify(player,times(2)).sendMessage(any(Component.class)); // Entry + result.
        }
    }
    @Test void disconnectFreesSessionWithoutTeleportAndFailedEntryDoesNotReserveArena() {
        World world=mock(World.class); Lobby lobby=mock(Lobby.class);
        GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
        Player player=player(world); games.start(player); games.disconnect(player); games.disconnect(player);
        assertFalse(player.getAllowFlight()); assertFalse(player.isFlying());
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
        assertFalse(player.getAllowFlight()); assertFalse(player.isFlying());
        verify(player).teleport(lobby.spawn()); verify(games,never()).start(any());
        var move=mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(player); when(move.getTo()).thenReturn(new Location(world,0,-2,0));
        listener.move(move); verify(move).setTo(lobby.spawn());
        var respawn=mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(player);
        player.setAllowFlight(true); player.setFlying(true);
        listener.respawn(respawn); verify(games).disconnect(player); verify(respawn).setRespawnLocation(lobby.spawn());
        assertFalse(player.getAllowFlight()); assertFalse(player.isFlying());
    }
    @Test void startMenuRejectsShiftBottomAndRepeatedClicksAndDefersEntry() {
        var plugin=plugin(); GameService games=mock(GameService.class); World world=mock(World.class);
        LobbyMenu menu=new LobbyMenu(plugin,games); Player player=player(world);
        when(player.hasPermission("moma.play")).thenReturn(true); when(player.isOnline()).thenReturn(true);
        var inventory=mock(org.bukkit.inventory.Inventory.class); var view=mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        var action=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        try (var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(()->Bukkit.createInventory(any(org.bukkit.inventory.InventoryHolder.class),eq(54),any(Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
            try (var items=mockConstruction(org.bukkit.inventory.ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) { menu.open(player); }
            var event=mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(LobbyMenu.JOIN); when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.SHIFT_LEFT);
            menu.click(event); verifyNoInteractions(scheduler);
            when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT); when(event.getRawSlot()).thenReturn(76);
            menu.click(event); verifyNoInteractions(scheduler);
            when(event.getRawSlot()).thenReturn(LobbyMenu.JOIN); menu.click(event); menu.click(event);
            verify(scheduler,times(1)).runTask(eq(plugin),action.capture()); verify(games,never()).start(any());
            action.getValue().run(); verify(games,times(1)).start(player);
            verify(event,times(4)).setCancelled(true);
        }
    }
    @Test void moreThanTwentyPlayersAllocateAdditionalArenas() throws Exception {
        World world=mock(World.class); ArenaMaps maps=mock(ArenaMaps.class);
        var available=new LinkedHashMap<String,ArenaMap>();
        for(int i=0;i<20;i++) available.put("a"+i,new ArenaMap("a"+i,world,i*128,64,0,new Grid(6)));
        when(maps.all()).thenAnswer(call->List.copyOf(available.values()));
        when(maps.get(anyString())).thenAnswer(call->available.get(call.getArgument(0)));
        when(maps.createNext(6)).thenAnswer(call->{
            String id="a"+available.size(); var map=new ArenaMap(id,world,available.size()*128,64,0,new Grid(6));
            available.put(id,map);return map;
        });
        when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call->mock(Chunk.class));
        GameService games=new GameService(plugin(),maps,CampaignRules.standard());
        for(int i=0;i<23;i++) { Player player=player(world);games.start(player);assertEquals("a"+i,games.session(player).arena.id()); }
        verify(maps,times(3)).createNext(6);assertEquals(23,available.size());
    }
    @Test void spectatingDoesNotOwnArenaAndOldSessionCannotBeWatchedAfterReuse() {
        World world=mock(World.class); Lobby lobby=spy(new Lobby(new Location(world,0,65,0),256));
        GameService games=new GameService(plugin(),maps(world),CampaignRules.standard(),lobby);
        Player owner=player(world),viewer=player(world);games.start(owner);GameSession session=games.session(owner);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(owner.getUniqueId())).thenReturn(owner);
            bukkit.when(()->Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
            assertEquals(0,games.spectatorCount(session.sessionId));
            games.spectate(viewer,session.sessionId);
            assertEquals(1,games.spectatorCount(session.sessionId));
            games.spectate(viewer,session.sessionId);
            assertEquals(1,games.spectatorCount(session.sessionId));
            assertTrue(games.watching(viewer)); assertFalse(games.playing(viewer));assertTrue(games.available("b"));
            verify(viewer,times(2)).setGameMode(GameMode.ADVENTURE);
            assertTrue(viewer.getAllowFlight()); assertTrue(viewer.isFlying());
            assertFalse(games.spectatorDestination(viewer,new Location(world,128,72,0)));
            assertTrue(games.spectatorDestination(viewer,new Location(world,4,72,4)));
            games.leave(owner);verify(lobby).send(viewer);assertFalse(games.watching(viewer));
            assertEquals(0,games.spectatorCount(session.sessionId));
            assertFalse(viewer.getAllowFlight()); assertFalse(viewer.isFlying());
            games.start(owner);assertNotEquals(session.sessionId,games.session(owner).sessionId);
            assertThrows(IllegalArgumentException.class,()->games.spectate(viewer,session.sessionId));
        }
    }
    @Test void noLobbyRestoresPreviousFlightOnLeaveAndSpectatorDisconnect() {
        World world=mock(World.class); GameService games=new GameService(plugin(),maps(world),CampaignRules.standard());
        Player owner=player(world), viewer=player(world);
        owner.setAllowFlight(true); // Preserve an existing flight permission without forcing it active after leaving.
        games.start(owner); games.leave(owner);
        assertTrue(owner.getAllowFlight()); assertFalse(owner.isFlying());
        games.start(owner); games.spectate(viewer,games.session(owner).sessionId);
        assertTrue(viewer.isFlying()); games.disconnect(viewer);
        assertEquals(GameMode.ADVENTURE,viewer.getGameMode());
        assertFalse(viewer.getAllowFlight()); assertFalse(viewer.isFlying());
    }
    @Test void spectatorMenuPaginatesBeyondFortyFiveAndKeepsExactSessionIdentity() {
        var plugin=plugin();GameService games=mock(GameService.class);var menu=new LobbyMenu(plugin,games);
        Player player=player(mock(World.class));when(player.hasPermission("moma.play")).thenReturn(true);when(player.isOnline()).thenReturn(true);
        var active=new ArrayList<GameService.SessionInfo>();
        for(int i=0;i<50;i++)active.add(new GameService.SessionInfo(UUID.randomUUID(),UUID.randomUUID(),"player"+i,"arena"+i,1,0));
        when(games.activeSessions()).thenReturn(active);
        var inventory=mock(org.bukkit.inventory.Inventory.class);var view=mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);var tasks=new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{tasks.add(call.getArgument(1));return null;});
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(()->Bukkit.createInventory(any(org.bukkit.inventory.InventoryHolder.class),eq(54),any(Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
            try(var items=mockConstruction(org.bukkit.inventory.ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {
                menu.open(player);verify(inventory).setItem(eq(LobbyMenu.NEXT),any());
                var event=mock(org.bukkit.event.inventory.InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
                when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT);when(event.getRawSlot()).thenReturn(LobbyMenu.NEXT);
                menu.click(event);tasks.removeFirst().run();
                verify(inventory).setItem(eq(LobbyMenu.PREVIOUS),any());
                when(event.getRawSlot()).thenReturn(4);menu.click(event);tasks.removeFirst().run();
                verify(games).spectate(player,active.get(49).sessionId());
            }
        }
    }
}
