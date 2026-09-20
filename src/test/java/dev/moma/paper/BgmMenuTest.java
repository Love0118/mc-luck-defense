package dev.moma.paper;

import dev.moma.bgm.Track;
import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BgmMenuTest {
    @TempDir Path temp;
    @Test void sixRowsPaginateFortyFiveSongsAndKeepControlsOutsideSongSlots()throws Exception {
        Files.writeString(temp.resolve("bgm.yml"),"refresh-token: ''");
        var plugin=mock(MomaPlugin.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        Player player=mock(Player.class);UUID owner=UUID.randomUUID();when(player.getUniqueId()).thenReturn(owner);
        World world=mock(World.class);when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        var data=mock(PersistentDataContainer.class);when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(),any(),any())).thenAnswer(call->call.getArgument(2));
        var games=mock(GameService.class);var session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        when(games.session(player)).thenReturn(session);
        var menus=new ArrayList<Inventory>();var slots=new IdentityHashMap<Inventory,Map<Integer,ItemStack>>();int[] tick={1};
        try(var bukkit=mockStatic(Bukkit.class);
            var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
            bukkit.when(Bukkit::getCurrentTick).thenAnswer(call->tick[0]);
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));
                slots.put(inventory,new HashMap<>());menus.add(inventory);
                doAnswer(c->{slots.get(inventory).put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any());return inventory;
            });
            try(var service=new BgmService(plugin,games)) {
                var initialized=BgmService.class.getDeclaredField("catalogLoaded");initialized.setAccessible(true);
                long deadline=System.nanoTime()+5_000_000_000L;while(!(boolean)initialized.get(service) && System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue((boolean)initialized.get(service));
                var tracks=new ArrayList<Track>();for(int i=0;i<46;i++)tracks.add(new Track("t"+i,i<3?owner:UUID.randomUUID(),"u","song"+i,"","https://example.com/"+i,"a".repeat(40),10));
                var field=BgmService.class.getDeclaredField("tracks");field.setAccessible(true);field.set(service,tracks);
                service.open(player,false,0);assertEquals(Set.of(0,1,2,45,46,48,49,53),slots.get(menus.getLast()).keySet());
                service.open(player,true,0);assertEquals(47,slots.get(menus.getLast()).size());assertTrue(slots.get(menus.getLast()).containsKey(44));
                assertFalse(slots.get(menus.getLast()).containsKey(45));
                var view=mock(InventoryView.class);when(view.getTopInventory()).thenAnswer(call->menus.getLast());
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);
                when(event.getRawSlot()).thenReturn(54);service.click(event);assertEquals(2,menus.size());
                when(event.getRawSlot()).thenReturn(53);service.click(event);
                assertEquals(Set.of(0,45,49),slots.get(menus.getLast()).keySet());
                tick[0]++;when(event.getRawSlot()).thenReturn(45);service.click(event);assertEquals(47,slots.get(menus.getLast()).size());
                tick[0]++;when(event.getRawSlot()).thenReturn(49);service.click(event);assertEquals(Set.of(0,1,2,45,46,48,49,53),slots.get(menus.getLast()).keySet());
                var lists=BgmService.class.getDeclaredField("playlists");lists.setAccessible(true);
                lists.set(service,Map.of(owner,new dev.moma.bgm.BgmPlaylist(tracks.stream().map(Track::id).toList(),dev.moma.bgm.BgmTimeline.Mode.MEDLEY,"t0")));
                service.open(player,false,0);assertTrue(slots.get(menus.getLast()).containsKey(44));assertTrue(slots.get(menus.getLast()).containsKey(52));
                tick[0]++;when(event.getRawSlot()).thenReturn(52);service.click(event);
                assertEquals(Set.of(0,45,46,48,49,51,53),slots.get(menus.getLast()).keySet());
                tick[0]++;when(event.getRawSlot()).thenReturn(51);service.click(event);assertTrue(slots.get(menus.getLast()).containsKey(44));
            }
        }
    }
}
