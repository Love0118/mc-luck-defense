package dev.moma.mining;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MiningRecoveryTest {
    @TempDir Path directory;
    private MiningStore store;
    private Path file;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private JavaPlugin plugin;
    private MiningRecovery recovery;
    private ItemStack original,cursor;
    private final Map<NamespacedKey,String> receipts=new HashMap<>();
    private MockedStatic<ItemStack> items;
    private MockedStatic<Bukkit> bukkit;
    @BeforeEach void setup()throws Exception {
        file=directory.resolve("mining.db");store=new MiningStore(file);
        plugin=mock(JavaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("outside");
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);when(inventory.getSize()).thenReturn(1);when(inventory.getHeldItemSlot()).thenReturn(3);
        original=mock(ItemStack.class);cursor=mock(ItemStack.class);when(inventory.getContents()).thenReturn(new ItemStack[]{original});when(player.getItemOnCursor()).thenReturn(cursor);
        when(player.getLocation()).thenReturn(new Location(world,4.5,71,-9.5,90,12));when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.getAllowFlight()).thenReturn(true);when(player.isFlying()).thenReturn(true);when(player.teleport(any(Location.class))).thenReturn(true);
        var pdc=mock(PersistentDataContainer.class);when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(i->receipts.get(i.getArgument(0)));
        doAnswer(i->{receipts.put(i.getArgument(0),i.getArgument(2));return null;}).when(pdc).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
        doAnswer(i->{receipts.remove(i.getArgument(0));return null;}).when(pdc).remove(any(NamespacedKey.class));
        items=mockStatic(ItemStack.class);
        items.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(i->new byte[]{(byte)(((ItemStack[])i.getArgument(0))[0]==original?1:2)});
        items.when(()->ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(i->new ItemStack[]{((byte[])i.getArgument(0))[0]==1?original:cursor});
        bukkit=mockStatic(Bukkit.class);bukkit.when(()->Bukkit.getWorld(world.getUID())).thenReturn(world);
        bukkit.when(()->Bukkit.getWorld("outside")).thenReturn(world);
        recovery=new MiningRecovery(plugin,store);
    }
    @AfterEach void cleanup()throws Exception { if(items!=null)items.close();if(bukkit!=null)bukkit.close();if(store!=null)store.close(); }
    @Test void captureCommitsBeforePlayerSaveAndRejectsOverwritingVault()throws Exception {
        doAnswer(i->{assertNotNull(store.profile(player.getUniqueId()).recovery());return null;}).when(player).saveData();
        recovery.capture(player);assertThrows(IOException.class,()->recovery.capture(player));
        verify(inventory,never()).clear();verify(player,never()).teleport(any(Location.class));
    }
    @Test void leaveRestoresInventoryCursorAndModeWithoutTeleportOrHostRouting()throws Exception {
        recovery.capture(player);clearInvocations(player,inventory);
        assertTrue(recovery.restore(player,false));verify(inventory).setContents(new ItemStack[]{original});verify(player).setItemOnCursor(cursor);
        verify(inventory).setHeldItemSlot(3);verify(player).setGameMode(GameMode.CREATIVE);verify(player).setAllowFlight(true);verify(player).setFlying(true);
        verify(player,never()).teleport(any(Location.class));assertNull(store.profile(player.getUniqueId()).recovery());assertTrue(receipts.isEmpty());
    }
    @Test void restartReceiptPreventsOverwritingInventoryAfterRestorationAlreadySaved()throws Exception {
        recovery.capture(player);
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var sql=connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_clear BEFORE UPDATE ON mining_profiles WHEN length(NEW.data)<length(OLD.data) BEGIN SELECT RAISE(ABORT,'simulated cleanup save failure'); END");
        }
        assertThrows(IOException.class,()->recovery.restore(player,false));assertFalse(receipts.isEmpty());assertNotNull(store.profile(player.getUniqueId()).recovery());
        store.close();store=new MiningStore(file);recovery=new MiningRecovery(plugin,store);
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var sql=connection.createStatement()){sql.execute("DROP TRIGGER reject_clear");}
        clearInvocations(player,inventory);assertTrue(recovery.restore(player,true));
        verify(inventory,never()).setContents(any(ItemStack[].class));verify(player,never()).teleport(any(Location.class));
        assertNull(store.profile(player.getUniqueId()).recovery());assertTrue(receipts.isEmpty());
    }
    @Test void unsuccessfulVanillaSaveKeepsVaultAndDoesNotTrustInMemoryReceipt()throws Exception {
        recovery.capture(player);doThrow(new IllegalStateException("disk full")).when(player).saveData();
        assertThrows(IllegalStateException.class,()->recovery.restore(player,false));assertNotNull(store.profile(player.getUniqueId()).recovery());assertTrue(receipts.isEmpty());
    }
    @Test void missingReturnWorldFailsClosedBeforeReplacingItems()throws Exception {
        recovery.capture(player);bukkit.when(()->Bukkit.getWorld(world.getUID())).thenReturn(null);bukkit.when(()->Bukkit.getWorld("outside")).thenReturn(null);
        clearInvocations(player,inventory);assertThrows(IOException.class,()->recovery.restore(player,true));
        verify(inventory,never()).setContents(any(ItemStack[].class));assertNotNull(store.profile(player.getUniqueId()).recovery());
        assertTrue(recovery.restore(player,false));assertNull(store.profile(player.getUniqueId()).recovery());
    }
    @Test void sharedItemsSaveOnceBeforeRestorationEvenIfOriginalPlayerSaveFails()throws Exception {
        var saves=new java.util.concurrent.atomic.AtomicInteger();
        recovery=new MiningRecovery(plugin,store,p->saves.incrementAndGet());recovery.capture(player);recovery.sharedLoaded(player);
        var failOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{if(receipts.containsKey(new NamespacedKey("momadefense","mining_restored"))&&failOnce.getAndSet(false))throw new IllegalStateException("disk full");return null;}).when(player).saveData();
        assertThrows(IllegalStateException.class,()->recovery.restore(player,false));assertEquals(1,saves.get());
        assertNotNull(store.profile(player.getUniqueId()).recovery());assertTrue(recovery.restore(player,false));
        assertEquals(1,saves.get());assertTrue(receipts.isEmpty());
    }
    @Test void failedEntryBeforeSharedLoadDoesNotSaveOutsideItemsIntoSharedVault()throws Exception {
        var saves=new java.util.concurrent.atomic.AtomicInteger();recovery=new MiningRecovery(plugin,store,p->saves.incrementAndGet());
        recovery.capture(player);assertTrue(recovery.restore(player,false));assertEquals(0,saves.get());
    }
}
