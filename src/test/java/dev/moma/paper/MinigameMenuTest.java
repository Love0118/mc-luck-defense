package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinigameMenuTest {
    private final Player player=mock(Player.class);
    private final GameService games=mock(GameService.class);
    private final Lobby lobby=mock(Lobby.class);
    private final MomaPlugin plugin=mock(MomaPlugin.class);
    private final LobbyMenu sessions=mock(LobbyMenu.class);
    private final dev.moma.mining.MiningGame mining=mock(dev.moma.mining.MiningGame.class);
    private final MinigameMenu menu=new MinigameMenu(plugin,games,lobby,sessions,mining);
    private final InventoryView view=mock(InventoryView.class);
    private final List<Runnable> tasks=new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> items;
    private Inventory current;

    @BeforeEach void setup() {
        when(player.isOnline()).thenReturn(true);when(player.hasPermission("moma.play")).thenReturn(true);
        when(games.quizCabinOwner(player)).thenReturn(true);when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenAnswer(c->current);
        bukkit=mockStatic(Bukkit.class);
        items=mockConstruction(ItemStack.class,(item,c)->{
            when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));when(item.getType()).thenReturn((Material)c.arguments().getFirst());
        });
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c->{tasks.add(c.getArgument(1));return null;});
        bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(net.kyori.adventure.text.Component.class)))
                .thenAnswer(c->{var inv=mock(Inventory.class);when(inv.getHolder()).thenReturn(c.getArgument(0));return inv;});
        doAnswer(c->{current=c.getArgument(0);return null;}).when(player).openInventory(any(Inventory.class));
        doAnswer(c->{var close=mock(InventoryCloseEvent.class);when(close.getInventory()).thenReturn(current);menu.close(close);current=null;return null;})
                .when(player).closeInventory();
    }
    @AfterEach void cleanup(){items.close();bukkit.close();}
    private InventoryClickEvent click(int slot,ClickType type) {
        var event=mock(InventoryClickEvent.class);var eventView=mock(InventoryView.class);
        when(event.getView()).thenReturn(eventView);when(eventView.getTopInventory()).thenReturn(current);
        when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(slot);when(event.getClick()).thenReturn(type);return event;
    }
    @Test void destinationQueuesOneSessionScreenAndDoesNotTravelOrStartAGame() {
        menu.open(player);var item=ArgumentCaptor.forClass(ItemStack.class);
        verify(current).setItem(eq(MinigameMenu.MOMA),item.capture());assertEquals(Material.GRASS_BLOCK,item.getValue().getType());
        var name=ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(item.getValue().getItemMeta()).displayName(name.capture());assertEquals(Ui.text("&a마운디"),name.getValue());
        verify(current).setItem(eq(MinigameMenu.CLOSE),item.capture());assertEquals(Material.BARRIER,item.getValue().getType());
        verify(current,times(3)).setItem(anyInt(),any(ItemStack.class));
        var event=click(MinigameMenu.MOMA,ClickType.LEFT);menu.click(event);menu.click(event);
        verify(event,times(2)).setCancelled(true);assertEquals(1,tasks.size());verify(games,never()).visitLobby(any());
        tasks.removeFirst().run();verify(sessions).open(player);verify(games,never()).visitLobby(player);verify(player).closeInventory();
        verify(games,never()).start(any());verify(games,never()).start(any(),anyBoolean());
    }
    @Test void bottomModifiedForeignAndDragEventsCannotChooseADestination() {
        menu.open(player);
        for(var type:List.of(ClickType.RIGHT,ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP)) {
            var event=click(MinigameMenu.MOMA,type);menu.click(event);verify(event).setCancelled(true);
        }
        for(int slot:new int[]{-999,0,27,40,49}) {var event=click(slot,ClickType.LEFT);menu.click(event);verify(event).setCancelled(true);}
        var foreign=click(MinigameMenu.MOMA,ClickType.LEFT);when(foreign.getWhoClicked()).thenReturn(mock(Player.class));menu.click(foreign);
        var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);menu.drag(drag);verify(drag).setCancelled(true);
        assertTrue(tasks.isEmpty());verify(games,never()).visitLobby(any());verify(sessions,never()).open(any());verify(mining,never()).join(any());
    }
    @Test void staleClosedOrNewlyActivePlayerCannotExecuteQueuedVisit() {
        menu.open(player);var stale=click(MinigameMenu.MOMA,ClickType.LEFT);menu.open(player);menu.click(stale);assertTrue(tasks.isEmpty());
        menu.click(click(MinigameMenu.MOMA,ClickType.LEFT));player.closeInventory();tasks.removeFirst().run();verify(games,never()).visitLobby(any());
        menu.open(player);menu.click(click(MinigameMenu.MOMA,ClickType.LEFT));when(games.playing(player)).thenReturn(true);
        tasks.removeFirst().run();verify(games,never()).visitLobby(any());
        clearInvocations(player);menu.open(player);verify(player,never()).openInventory(any(Inventory.class));
        verify(sessions,never()).open(any());
    }
    @Test void closeButtonAndPermissionLossNeverTravel() {
        menu.open(player);menu.click(click(MinigameMenu.CLOSE,ClickType.LEFT));tasks.removeFirst().run();verify(player).closeInventory();
        menu.open(player);menu.click(click(MinigameMenu.MOMA,ClickType.LEFT));when(player.hasPermission("moma.play")).thenReturn(false);
        tasks.removeFirst().run();verify(games,never()).visitLobby(any());
        clearInvocations(player);menu.open(player);verify(player,never()).openInventory(any(Inventory.class));
        verify(sessions,never()).open(any());
    }
    @Test void lobbyAndSpectatorAreEligibleButUnrelatedWorldIsNot() {
        when(games.quizCabinOwner(player)).thenReturn(false);menu.open(player);verify(player,never()).openInventory(any(Inventory.class));
        when(lobby.contains(any())).thenReturn(true);menu.open(player);verify(player).openInventory(any(Inventory.class));
        when(lobby.contains(any())).thenReturn(false);when(games.watching(player)).thenReturn(true);menu.open(player);
        verify(player,times(2)).openInventory(any(Inventory.class));
        when(player.isOnline()).thenReturn(false);menu.open(player);verify(player,times(2)).openInventory(any(Inventory.class));
    }
    @Test void rejectedVisitReportsErrorAfterClosingInventory() {
        doThrow(new IllegalStateException("screen rejected")).when(sessions).open(player);
        menu.open(player);menu.click(click(MinigameMenu.MOMA,ClickType.LEFT));tasks.removeFirst().run();
        verify(player).closeInventory();verify(player).sendMessage(Ui.text("&cscreen rejected"));
    }
    @Test void mineDestinationEntersOnceAndRemainsAccessibleInsideMine(){
        when(games.quizCabinOwner(player)).thenReturn(false);when(mining.owns(player)).thenReturn(true);
        menu.open(player);menu.click(click(MinigameMenu.MINE,ClickType.LEFT));tasks.removeFirst().run();
        verify(mining).join(player);verify(games,never()).visitLobby(any());verify(sessions,never()).open(any());
    }
}
