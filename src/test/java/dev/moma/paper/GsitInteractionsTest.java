package dev.moma.paper;

import dev.moma.core.CampaignRules;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.mockito.Mockito.*;

class GsitInteractionsTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<SessionTools> tools;
    private MockedConstruction<EntityAdapter> entities;
    private GameService games;
    private GameListener gameListener;
    private LobbyListener lobbyListener;
    private Player player;
    private PluginManager plugins;
    private PlayerInventory inventory;
    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);tools=mockConstruction(SessionTools.class);entities=mockConstruction(EntityAdapter.class);
        plugins=mock(PluginManager.class);bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);when(plugins.isPluginEnabled("GSit")).thenReturn(true);
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        games=spy(new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard()));
        var lobby=mock(Lobby.class);when(lobby.contains(any())).thenReturn(true);
        lobbyListener=new LobbyListener(lobby,games,mock(LobbyMenu.class),mock(MinigameMenu.class));
        gameListener=new GameListener(games,mock(ArenaMaps.class),mock(ShopMenu.class));
        player=mock(Player.class);when(player.isValid()).thenReturn(true);when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);
        var empty=mock(ItemStack.class);when(empty.getType()).thenReturn(Material.AIR);when(inventory.getItemInMainHand()).thenReturn(empty);
        doReturn(true).when(games).playing(player);
    }
    @AfterEach void cleanup(){entities.close();tools.close();bukkit.close();}
    private PlayerInteractEvent seat(Material material) {
        var event=mock(PlayerInteractEvent.class);var block=mock(Block.class);when(block.getType()).thenReturn(material);
        when(event.getPlayer()).thenReturn(player);when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);when(event.getBlockFace()).thenReturn(BlockFace.UP);when(event.getClickedBlock()).thenReturn(block);return event;
    }
    private PlayerInteractAtEntityEvent passenger() {
        var event=mock(PlayerInteractAtEntityEvent.class);var target=mock(Player.class);
        when(target.isValid()).thenReturn(true);when(target.isOnline()).thenReturn(true);
        when(event.getPlayer()).thenReturn(player);when(event.getRightClicked()).thenReturn(target);when(event.getHand()).thenReturn(EquipmentSlot.HAND);return event;
    }
    private void blockListeners(PlayerInteractEvent event){lobbyListener.interact(event);gameListener.interact(event);}
    private void entityListeners(PlayerInteractAtEntityEvent event){lobbyListener.interactAtEntity(event);gameListener.entityInteractAt(event);}
    @Test void harmlessSeatsAndPlayersPassBothListenersWithoutUncancelling() {
        for(var material:new Material[]{Material.OAK_STAIRS,Material.STONE_SLAB,Material.WHITE_CARPET,Material.MOSS_CARPET,Material.SNOW}) {
            var event=seat(material);blockListeners(event);verify(event,never()).setCancelled(anyBoolean());
            when(event.isCancelled()).thenReturn(true);blockListeners(event);verify(event,never()).setCancelled(anyBoolean());
        }
        var event=passenger();entityListeners(event);verify(event,never()).setCancelled(anyBoolean());
        when(event.isCancelled()).thenReturn(true);entityListeners(event);verify(event,never()).setCancelled(anyBoolean());
        var generic=mock(PlayerInteractEntityEvent.class);when(generic.getPlayer()).thenReturn(player);
        var target=event.getRightClicked();when(generic.getRightClicked()).thenReturn(target);when(generic.getHand()).thenReturn(EquipmentSlot.HAND);
        lobbyListener.interactEntity(generic);gameListener.entityInteract(generic);verify(generic,never()).setCancelled(anyBoolean());
    }
    @Test void heldItemsContainersSideClicksOffhandSneakingAndDisabledGsitStayProtected() {
        var held=seat(Material.OAK_STAIRS);when(held.getItem()).thenReturn(mock(ItemStack.class));blockListeners(held);verify(held,times(2)).setCancelled(true);
        var container=seat(Material.CHEST);blockListeners(container);verify(container,times(2)).setCancelled(true);
        var side=seat(Material.OAK_STAIRS);when(side.getBlockFace()).thenReturn(BlockFace.NORTH);blockListeners(side);verify(side,times(2)).setCancelled(true);
        var off=seat(Material.OAK_STAIRS);when(off.getHand()).thenReturn(EquipmentSlot.OFF_HAND);blockListeners(off);verify(off,times(2)).setCancelled(true);
        when(player.isSneaking()).thenReturn(true);var sneak=seat(Material.OAK_STAIRS);blockListeners(sneak);verify(sneak,times(2)).setCancelled(true);
        when(player.isSneaking()).thenReturn(false);when(plugins.isPluginEnabled("GSit")).thenReturn(false);
        var disabled=seat(Material.OAK_STAIRS);blockListeners(disabled);verify(disabled,times(2)).setCancelled(true);
    }
    @Test void toolsNpcManagedEntitiesAndInvalidPlayerTargetsStayProtected() {
        var held=passenger();var tool=mock(ItemStack.class);when(tool.getType()).thenReturn(Material.COMPASS);when(inventory.getItemInMainHand()).thenReturn(tool);
        entityListeners(held);verify(held,times(2)).setCancelled(true);when(tool.getType()).thenReturn(Material.AIR);
        var npc=passenger();when(npc.getRightClicked()).thenReturn(mock(Mannequin.class));entityListeners(npc);verify(npc,times(2)).setCancelled(true);
        var fake=passenger();when(((Player)fake.getRightClicked()).isOnline()).thenReturn(false);entityListeners(fake);verify(fake,times(2)).setCancelled(true);
        var managed=passenger();when(games.entities.managed(managed.getRightClicked())).thenReturn(true);entityListeners(managed);verify(managed).setCancelled(true);
        var off=passenger();when(off.getHand()).thenReturn(EquipmentSlot.OFF_HAND);entityListeners(off);verify(off,times(2)).setCancelled(true);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);var spectator=passenger();entityListeners(spectator);verify(spectator,times(2)).setCancelled(true);
    }
}
