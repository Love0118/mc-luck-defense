package dev.moma.mining;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Local ore movement is vanilla; ground transfers are backed by durable single-use claims. */
final class MiningCargoDrops implements Listener,AutoCloseable {
    private static final Map<MiningGame,MiningCargoDrops> ACTIVE=new IdentityHashMap<>();
    private final MiningGame game;
    private final JavaPlugin plugin;
    private final MiningStore store;
    private final MiningItems items;
    private final MiningGame.Host host;
    private final CargoTransfers transfers;
    private MiningCargoDrops(MiningGame game,JavaPlugin plugin,MiningStore store,MiningItems items,MiningGame.Host host)throws IOException{
        this.game=game;this.plugin=plugin;this.store=store;this.items=items;this.host=host;
        transfers=new CargoTransfers(plugin.getDataFolder().toPath().resolve("mining/mining.db"),store);
    }
    static void start(MiningGame game,JavaPlugin plugin,MiningStore store,MiningItems items,MiningGame.Host host)throws IOException{
        if(ACTIVE.containsKey(game))return;
        var listener=new MiningCargoDrops(game,plugin,store,items,host);
        try{plugin.getServer().getPluginManager().registerEvents(listener,plugin);ACTIVE.put(game,listener);}
        catch(RuntimeException failure){listener.close();throw failure;}
    }
    static void stop(MiningGame game){var listener=ACTIVE.remove(game);if(listener!=null)listener.close();}
    private boolean eligible(Player player){return game.sharesInventory(player)||host.inSharedLobby(player);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent event){
        var entity=event.getItemDrop();var ore=CargoStacks.ore(entity.getItemStack());if(ore==null)return;
        if(!eligible(event.getPlayer())){event.setCancelled(true);return;}
        try{transfers.drop(event.getPlayer().getUniqueId(),entity.getUniqueId(),ore,entity.getItemStack().getAmount());}
        catch(IOException|RuntimeException failure){event.setCancelled(true);fail(event.getPlayer(),failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){
        var entity=event.getItem();var ore=CargoStacks.ore(entity.getItemStack());if(ore==null)return;
        event.setCancelled(true);
        if(!(event.getEntity() instanceof Player player)||!eligible(player))return;
        try{
            var claim=transfers.claim(entity.getUniqueId());
            if(claim==null||claim.ore()!=ore){entity.remove();return;}
            var profile=store.profile(player.getUniqueId());
            int amount=Math.min(entity.getItemStack().getAmount(),claim.amount());
            while(amount>0&&!items.canCarry(player,profile,ore,amount))amount--;
            int received=transfers.pickup(entity.getUniqueId(),player.getUniqueId(),amount);if(received==0)return;
            var remaining=transfers.claim(entity.getUniqueId());
            if(remaining==null)entity.remove();else{var stack=entity.getItemStack();stack.setAmount(remaining.amount());entity.setItemStack(stack);}
            items.cargo(player,store.profile(player.getUniqueId()));
        }catch(IOException|RuntimeException failure){fail(player,failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;
        boolean ore=CargoStacks.ore(event.getCurrentItem())!=null||CargoStacks.ore(event.getCursor())!=null
                ||event.getHotbarButton()>=0&&CargoStacks.ore(player.getInventory().getItem(event.getHotbarButton()))!=null
                ||event.getClick()==ClickType.SWAP_OFFHAND&&CargoStacks.ore(player.getInventory().getItemInOffHand())!=null;
        if(!ore||event.getClickedInventory()==null)return;
        var top=event.getView().getTopInventory();
        if(event.getClickedInventory()!=player.getInventory()||!(top instanceof PlayerInventory||top instanceof CraftingInventory crafting&&crafting.getMatrix()!=null&&crafting.getMatrix().length==4))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){
        if(CargoStacks.ore(event.getOldCursor())!=null&&event.getRawSlots().stream().anyMatch(slot->event.getView().getInventory(slot)!=event.getWhoClicked().getInventory()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent event){
        if(event.getAction().isRightClick()&&CargoStacks.ore(event.getItem())!=null)event.setUseItemInHand(Event.Result.DENY);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void hopper(InventoryMoveItemEvent event){if(CargoStacks.ore(event.getItem())!=null)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void hopper(InventoryPickupItemEvent event){if(CargoStacks.ore(event.getItem().getItemStack())!=null)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void merge(ItemMergeEvent event){if(CargoStacks.ore(event.getEntity().getItemStack())!=null||CargoStacks.ore(event.getTarget().getItemStack())!=null)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void despawn(ItemDespawnEvent event){
        if(CargoStacks.ore(event.getEntity().getItemStack())!=null)try{transfers.retire(event.getEntity().getUniqueId());}catch(IOException failure){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Could not retire ore claim",failure);}
    }
    private void fail(Player player,Throwable failure){
        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Ore transfer retained for retry",failure);
        player.sendMessage(MiningItems.text("&c광물 이동을 저장하지 못했습니다. 다시 시도하세요."));
    }
    @Override public void close(){
        HandlerList.unregisterAll(this);
        try{transfers.close();}catch(IOException failure){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Ore transfer storage close failed",failure);}
    }
}
