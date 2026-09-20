package dev.moma.paper;

import dev.moma.core.*;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTest {
    private MomaPlugin plugin;
    private GameService games;
    private ArenaMaps maps;
    private ShopMenu shop;
    private GameListener listener;
    private Player player;
    @BeforeEach void setup() {
        plugin = mock(MomaPlugin.class); when(plugin.getName()).thenReturn("MCLuckDefense"); when(plugin.namespace()).thenReturn("momadefense");
        maps = mock(ArenaMaps.class);
        games = spy(new GameService(plugin, maps, CampaignRules.standard()));
        shop = mock(ShopMenu.class);
        listener = new GameListener(games, maps, shop);
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        doReturn(true).when(games).usingMoveTool(player);
        doReturn(false).when(games).usingSellTool(player);
        doReturn(false).when(games).usingSoundTool(player);
        doReturn(false).when(games).usingBgmTool(player);
        doReturn(false).when(games).usingLeaveTool(player);
        doReturn(false).when(games).usingManageTool(player);
    }
    @Test void managementItemOpensShopOncePerTickWithMainHand() {
        doReturn(true).when(games).playing(player);doReturn(true).when(games).usingManageTool(player);
        var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);listener.interact(event);verifyNoInteractions(shop);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);listener.interact(event);listener.interact(event);verify(shop).open(player);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);listener.interact(event);verify(shop,times(2)).open(player);
        }
    }
    @Test void swapOpensShopOnlyForParticipantsAndCancelsItemSwap() {
        var event = mock(PlayerSwapHandItemsEvent.class); when(event.getPlayer()).thenReturn(player);
        listener.swap(event); verifyNoInteractions(shop);
        doReturn(true).when(games).playing(player);
        listener.swap(event); verify(event).setCancelled(true); verify(shop).open(player);
    }
    @Test void rightClickDoesNotSelectOrMoveAndParticipantBlocksCannotBeBroken() {
        doReturn(true).when(games).playing(player);
        var interaction = mock(PlayerInteractEntityEvent.class);
        when(interaction.getPlayer()).thenReturn(player);
        Entity entity = mock(Entity.class);
        when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        when(interaction.getRightClicked()).thenReturn(entity);
        listener.entityInteract(interaction); verify(interaction).setCancelled(true);
        verify(games, never()).select(any(), any()); verify(games, never()).move(any(), any());
        var block = mock(BlockBreakEvent.class); when(block.getPlayer()).thenReturn(player);
        listener.breakBlock(block); verify(block).setCancelled(true);
    }
    @Test void managedEntityLeftClickSelectsOncePerTickAndCancelsDamage() {
        doReturn(true).when(games).playing(player); doNothing().when(games).select(any(), any());
        Entity entity = mock(Entity.class); UUID id = UUID.randomUUID(); when(entity.getUniqueId()).thenReturn(id);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING))).thenReturn(true);
        var event = mock(PrePlayerAttackEntityEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getAttacked()).thenReturn(entity);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(30);
            listener.attack(event); listener.attack(event);
            verify(games, times(1)).select(player, id); verify(event, times(2)).setCancelled(true);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(31); listener.attack(event);
            verify(games, times(2)).select(player, id);
        }
    }
    @Test void leftClickAirMovesUsingTheFirstBlockOnTheRay() {
        doReturn(true).when(games).playing(player); doNothing().when(games).move(any(), any());
        World world = mock(World.class); when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0, 66, 0));
        var block = mock(org.bukkit.block.Block.class);
        var hit = new org.bukkit.util.RayTraceResult(new org.bukkit.util.Vector(3, 64, 0), block, org.bukkit.block.BlockFace.UP);
        when(world.rayTrace(any(Location.class), any(org.bukkit.util.Vector.class), eq(24.0), eq(FluidCollisionMode.NEVER), eq(true), eq(0.15), any())).thenReturn(hit);
        var event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(40);
            listener.interact(event);
            verify(games).move(player, block); verify(event).setCancelled(true);
        }
    }
    @Test void shopRejectsDuplicateShiftAndBottomInventoryClicks() {
        ShopMenu actualShop = new ShopMenu(plugin, games);
        World world = mock(World.class);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0)); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        GameSession session = new GameSession(player, new ArenaMap("a", world, 0, 64, 0, new Grid(5)), CampaignRules.standard());
        doReturn(session).when(games).session(player);
        doNothing().when(games).summon(player);
        Inventory inventory = mock(Inventory.class); InventoryView view = mock(InventoryView.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(net.kyori.adventure.text.Component.class)))
                    .thenAnswer(invocation -> { when(inventory.getHolder()).thenReturn(invocation.getArgument(0)); return inventory; });
            // Server-backed item construction is outside this event routing test.
            var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
            try (var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
                actualShop.open(player);
            }
            var event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(11); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
            actualShop.click(event); verify(games, never()).summon(player);
            when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(38);
            actualShop.click(event); verify(games, never()).summon(player);
            when(event.getRawSlot()).thenReturn(11);
            actualShop.click(event); actualShop.click(event); verify(games, times(1)).summon(player);
            verify(player,times(1)).playSound(any(Location.class),eq(Ui.Cue.OPEN.sound),eq(SoundCategory.MASTER),eq(Ui.Cue.OPEN.volume),eq(Ui.Cue.OPEN.pitch));
            verify(event, times(4)).setCancelled(true);
            var refresh=org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskLater(eq(plugin),refresh.capture(),eq(1L));
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);
            try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) { refresh.getValue().run(); }
            actualShop.click(event);verify(games,times(2)).summon(player);
            var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
            actualShop.drag(drag); verify(drag).setCancelled(true);
        }
    }
    @Test void autoSaleGuiRoutesExactGradeOnceAndRejectsBottomInventory() {
        ShopMenu actualShop = new ShopMenu(plugin, games); World world = mock(World.class);
        when(player.getLocation()).thenReturn(new Location(world,0,70,0)); when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        GameSession session = new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        doReturn(session).when(games).session(player); doNothing().when(games).toggleAutoSell(any(),any());
        Inventory inventory=mock(Inventory.class); InventoryView view=mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(net.kyori.adventure.text.Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
            try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {actualShop.open(player);}
            var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(20);when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
            actualShop.click(event);verify(games,never()).toggleAutoSell(any(),any());
            when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(48);
            actualShop.click(event);verify(games,never()).toggleAutoSell(any(),any());
            when(event.getRawSlot()).thenReturn(20);actualShop.click(event);actualShop.click(event);
            verify(games,times(1)).toggleAutoSell(player,Rarity.RARE);verify(event,times(4)).setCancelled(true);
        }
    }
    @Test void speedButtonCyclesOncePerClickAndRejectsMenusFromAnOldSession() {
        ShopMenu actualShop=new ShopMenu(plugin,games); World world=mock(World.class);
        when(player.getLocation()).thenReturn(new Location(world,0,70,0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        GameSession session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        doReturn(session).when(games).session(player);
        Inventory inventory=mock(Inventory.class); InventoryView view=mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);var tasks=new ArrayList<Runnable>();
        when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(1L))).thenAnswer(call->{tasks.add(call.getArgument(1));return null;});
        int[] tick={10};
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenAnswer(call->tick[0]);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(net.kyori.adventure.text.Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
            try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {
                actualShop.open(player);
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
                when(event.getRawSlot()).thenReturn(8);when(event.getClick()).thenReturn(ClickType.LEFT);
                for(int expected:new int[]{2,4,8,1}) {
                    actualShop.click(event);actualShop.click(event);assertEquals(expected,session.speed());
                    tick[0]++;tasks.removeFirst().run();
                }
                doReturn(new GameSession(player,session.map,CampaignRules.standard())).when(games).session(player);
                actualShop.click(event);verify(games,times(4)).speed(eq(player),anyInt());
            }
        }
    }
    @Test void viewerBedUsesMainHandOnceAndBlocksOtherInteractions() {
        doReturn(true).when(games).watching(player);
        doReturn(true).when(games).usingLeaveTool(player);
        doNothing().when(games).leave(player);
        when(player.getLocation()).thenReturn(new Location(mock(World.class),0,70,0));
        var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);
            when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND); listener.interact(event);verify(games,never()).leave(player);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);listener.interact(event);listener.interact(event);
            verify(games,times(1)).leave(player);verify(event,times(3)).setCancelled(true);
        }
        var damage=mock(EntityDamageEvent.class);when(damage.getEntity()).thenReturn(player);
        when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        listener.damage(damage);verify(damage).setCancelled(true);
        var drop=mock(PlayerDropItemEvent.class);when(drop.getPlayer()).thenReturn(player);listener.drop(drop);verify(drop).setCancelled(true);
    }
    @Test void saleToolIgnoresOffhandAndDeduplicatesAirBlockAndEntityEvents() {
        doReturn(true).when(games).playing(player);doReturn(true).when(games).usingSellTool(player);
        doReturn(false).when(games).usingMoveTool(player);doNothing().when(games).sell(player);
        Entity entity=mock(Entity.class);when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        var right=mock(PlayerInteractEvent.class);when(right.getPlayer()).thenReturn(player);when(right.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        var at=mock(PlayerInteractAtEntityEvent.class);when(at.getPlayer()).thenReturn(player);when(at.getRightClicked()).thenReturn(entity);when(at.getHand()).thenReturn(EquipmentSlot.HAND);
        var interact=mock(PlayerInteractEntityEvent.class);when(interact.getPlayer()).thenReturn(player);when(interact.getRightClicked()).thenReturn(entity);when(interact.getHand()).thenReturn(EquipmentSlot.HAND);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);
            when(right.getHand()).thenReturn(EquipmentSlot.OFF_HAND);listener.interact(right);verify(games,never()).sell(player);
            when(right.getHand()).thenReturn(EquipmentSlot.HAND);listener.interact(right);listener.entityInteractAt(at);listener.entityInteract(interact);
            verify(games,times(1)).sell(player);verify(games,never()).select(any(),any());
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);when(right.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);listener.interact(right);
            verify(games,times(2)).sell(player);
        }
        var inventory=mock(InventoryClickEvent.class);when(inventory.getWhoClicked()).thenReturn(player);listener.inventory(inventory);verify(inventory).setCancelled(true);
    }
}
