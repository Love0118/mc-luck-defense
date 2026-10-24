package dev.moma.mining;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Dedicated survival mine. Host owns routing, shared shop, navigation and wallet authority. */
public final class MiningGame implements Listener,AutoCloseable {
    public interface Host {
        long balance(Player player);
        /** Durable idempotent debit: replay of a successful transaction must return true. */
        boolean spend(UUID player,String transaction,long amount);
        /** Durable idempotent credit, including transaction IDs ending in :refund. */
        void reward(UUID player,String transaction,long amount);
        void prepareEntry(Player player);
        void giveTools(Player player);
        void openSharedShop(Player player);
        default void openMineShop(Player player,Runnable openSales,Runnable openUpgrade){openSharedShop(player);}
        void returnToHub(Player player);
    }
    private final JavaPlugin plugin;
    private final Host host;
    private final Set<UUID> active=new HashSet<>(),blocked=new HashSet<>(),transitioning=new HashSet<>();
    private final ArrayDeque<Runnable> menuActions=new ArrayDeque<>();
    private MiningStore store;
    private MiningEconomy economy;
    private MiningRecovery recovery;
    private MiningItems items;
    private MiningWorld cave;
    private MiningNpc npc;
    private BukkitTask task;
    private boolean started,closed,worldFailed;
    private int ticks;
    public MiningGame(JavaPlugin plugin,Host host){this.plugin=Objects.requireNonNull(plugin);this.host=Objects.requireNonNull(host);}
    /** Registers this listener and owns exactly one task. Do not separately register the same listener. */
    public void start() {
        mainThread();if(started)return;if(closed)throw new IllegalStateException("Mining is closed");
        try {
            store=new MiningStore(plugin.getDataFolder().toPath().resolve("mining/mining.db"));
            recovery=new MiningRecovery(plugin,store);economy=new MiningEconomy(store);items=new MiningItems(plugin);
            if(!"2".equals(store.meta("pickaxe-reset"))){
                for(UUID id:store.players())try{economy.settle(id,wallet(id));}
                catch(IllegalArgumentException unpaid){if(store.profile(id).pending()!=null)throw unpaid;}
                store.resetPickaxes();
            }
            // Recover online reload/crash survivors before generating terrain or allowing new entry.
            for(Player player:Bukkit.getOnlinePlayers())recover(player);
            cave=new MiningWorld(plugin,store);npc=new MiningNpc(cave);
            plugin.getServer().getPluginManager().registerEvents(this,plugin);started=true;
            task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
        } catch(IOException | RuntimeException failure) {
            close();throw new IllegalStateException("Mining could not start safely",failure);
        }
    }
    public boolean owns(Player player){return player!=null && (active.contains(player.getUniqueId()) || blocked.contains(player.getUniqueId()));}
    public boolean contains(Location location){return cave!=null && cave.contains(location);}
    /** Call before normal hub-join routing if that routing runs at LOWEST priority. Never routes via Host. */
    public boolean recover(Player player) {
        mainThread();if(recovery==null || active.contains(player.getUniqueId()))return false;
        UUID id=player.getUniqueId();transitioning.add(id);
        try { boolean restored=recovery.restore(player,true);blocked.remove(id);items.remove(player);return restored; }
        catch(IOException | RuntimeException failure){blocked.add(id);fail(player,failure);return false;}
        finally{transitioning.remove(id);}
    }
    public void join(Player player) {
        mainThread();if(!started || closed || worldFailed || cave==null || !cave.ready()){message(player,"광산 준비 중입니다.");return;}
        UUID id=player.getUniqueId();if(active.contains(id))return;
        if(blocked.contains(id)){recover(player);if(blocked.contains(id))return;}
        boolean prepared=false,snapshot=false;
        transitioning.add(id);
        try {
            if(store.profile(id).recovery()!=null && !recovery.restore(player,true))throw new IOException("Unresolved mining inventory");
            economy.settle(id,wallet(player));
            host.prepareEntry(player);prepared=true; // Must release Noqui cabin and wardrobe before capturing.
            recovery.capture(player);snapshot=true;
            player.setItemOnCursor(null);player.closeInventory();player.getInventory().clear();
            active.add(id);player.setGameMode(GameMode.SURVIVAL);player.setFlying(false);player.setAllowFlight(false);player.setFallDistance(0);
            host.giveTools(player);var profile=store.profile(id);items.give(player,profile);items.cargo(player,profile);
            if(!player.teleport(cave.spawn()))throw new IOException("Mining entry teleport was refused");
            player.saveData();
        } catch(IOException | RuntimeException failure) {
            active.remove(id);boolean restored=!snapshot;
            // capture may commit its vault before saveData throws; inspect it rather than trusting the flag.
            try { if(store.profile(id).recovery()!=null)recovery.restore(player,false);restored=true; }
            catch(IOException | RuntimeException restoreFailure){failure.addSuppressed(restoreFailure);blocked.add(id);restored=false;}
            fail(player,failure);
            if(prepared && restored)try{host.returnToHub(player);}catch(RuntimeException routeFailure){plugin.getLogger().log(Level.SEVERE,"Mining entry recovery routing failed",routeFailure);}
        } finally{transitioning.remove(id);}
    }
    /**
     * Restores the pre-mine inventory/mode/flight/slot without teleporting or invoking Host.
     * The vault is always restored, including restore=false: dropping a durable inventory is unsafe.
     * Quit, restart and shutdown recovery restore the original location through a separate path.
     */
    public void leave(Player player,boolean restore) {
        mainThread();if(store==null)return;UUID id=player.getUniqueId();
        transitioning.add(id);
        try {
            if(!active.contains(id) && !blocked.contains(id) && store.profile(id).recovery()==null)return;
            recovery.restore(player,false);active.remove(id);blocked.remove(id);items.remove(player);
        } catch(IOException | RuntimeException failure){blocked.add(id);fail(player,failure);throw new IllegalStateException("Mining inventory recovery is pending",failure);}
        finally{transitioning.remove(id);}
    }
    private void returnToHub(Player player){leave(player,true);host.returnToHub(player);}
    private void tick() {
        if(closed)return;
        ticks++;
        for(int i=0;i<32 && !menuActions.isEmpty();i++)menuActions.removeFirst().run();
        if(!worldFailed)try{cave.tick();if(ticks%5==0)npc.tick();}
        catch(IOException | RuntimeException failure){worldFailed=true;plugin.getLogger().log(Level.SEVERE,"Mining world edits stopped; durable state retained",failure);}
        if(ticks%20!=0)return;
        for(Player player:cave.world.getPlayers()) {
            UUID id=player.getUniqueId();if(transitioning.contains(id))continue;
            if(active.contains(id)) {
                if(player.getGameMode()!=GameMode.SURVIVAL)player.setGameMode(GameMode.SURVIVAL);
                if(!cave.contains(player.getLocation()))player.teleport(cave.spawn());
            } else if(!blocked.contains(id)) {
                // No unmanaged visitors can use this dedicated world, even in creative mode.
                try{host.returnToHub(player);}catch(RuntimeException failure){fail(player,failure);}
            }
        }
    }
    private MiningEconomy.Wallet wallet(Player player){return wallet(player.getUniqueId());}
    private MiningEconomy.Wallet wallet(UUID player){return new MiningEconomy.Wallet(){
        public boolean spend(String transaction,long amount){return host.spend(player,transaction,amount);}
        public void reward(String transaction,long amount){host.reward(player,transaction,amount);}
    };}
    private Map<MiningRules.Ore,Long> prices(){var prices=new EnumMap<MiningRules.Ore,Long>(MiningRules.Ore.class);for(var ore:MiningRules.Ore.values())prices.put(ore,MiningRules.price(plugin.getConfig().getLong("mining.prices."+ore.name().toLowerCase(Locale.ROOT),ore.price)));return prices;}
    private boolean eligible(Player player){return !closed && active.contains(player.getUniqueId()) && !blocked.contains(player.getUniqueId());}
    private static final class Menu implements InventoryHolder {
        final UUID owner;final Map<Integer,Runnable> actions=new HashMap<>();Inventory inventory;
        boolean consumed;
        Menu(Player player){owner=player.getUniqueId();}
        @Override public Inventory getInventory(){return inventory;}
    }
    private Menu menu(Player player,String title){var menu=new Menu(player);menu.inventory=Bukkit.createInventory(menu,27,MiningItems.text(title));return menu;}
    private void button(Menu menu,int slot,Material type,String label,Runnable action,String... lore){menu.inventory.setItem(slot,MiningItems.icon(type,label,lore));menu.actions.put(slot,action);}
    private void openShop(Player player) {
        if(eligible(player))host.openMineShop(player,()->openCargo(player),()->openUpgrade(player));
    }
    private void openCargo(Player player) {
        if(!eligible(player))return;
        try {
            var p=store.profile(player.getUniqueId());var menu=menu(player,"광산 상점");
            button(menu,10,Material.CHEST,"상점",()->openShop(player));
            var cargoLore=new ArrayList<String>();
            for(var ore:MiningRules.Ore.values())cargoLore.add("&7"+ore.label+" &f"+p.cargo().getOrDefault(ore,0L)+" &8/ &e✦ "+prices().get(ore));
            button(menu,12,Material.EMERALD,"&a광석 판매 &e✦ "+MiningRules.sale(p.cargo(),prices()),()->{
                try{long amount=economy.sell(player.getUniqueId(),prices(),wallet(player));items.cargo(player,store.profile(player.getUniqueId()));if(amount>0){message(player,"&e✦ "+amount);MiningItems.sound(player,"entity.experience_orb.pickup",1.3f);}openShop(player);}
                catch(IOException | RuntimeException failure){fail(player,failure);}
            },cargoLore.toArray(String[]::new));
            button(menu,14,Material.IRON_PICKAXE,"곡괭이 강화",()->openUpgrade(player));
            button(menu,16,Material.OAK_DOOR,"돌아가기",()->returnToHub(player));player.openInventory(menu.inventory);
        } catch(IOException | RuntimeException failure){fail(player,failure);}
    }
    public void openUpgrade(Player player) {
        if(!eligible(player))return;
        try {
            var p=store.profile(player.getUniqueId());var menu=menu(player,"곡괭이 강화");
            menu.inventory.setItem(4,MiningItems.icon(Material.NETHER_STAR,"별빛 ✦ "+host.balance(player)));
            for(var upgrade:MiningRules.Upgrade.values()) {
                int level=upgrade==MiningRules.Upgrade.SPEED?p.speed():p.fortune();boolean capped=level>=MiningRules.cap(upgrade);
                String label=upgrade==MiningRules.Upgrade.SPEED?"&b속도 &f"+p.speed()+"%":"&d행운 &f"+p.fortune();
                var offer=capped?null:MiningRules.offer(upgrade,level);
                String[] lore=capped?new String[]{"&a최대 강화"}:new String[]{"&e✦ "+offer.cost(),"&a성공 "+offer.success()+"% &8/ &7유지 "+(100-offer.success()-offer.destruction())+"%",
                        offer.destruction()==0?"&7파괴 없음":"&c파괴 "+offer.destruction()+"% &8/ &7속도·행운 초기화",upgrade==MiningRules.Upgrade.SPEED?"&b성공 시 +1~10%p":"&d성공 시 행운 +I"};
                button(menu,upgrade==MiningRules.Upgrade.SPEED?11:15,upgrade==MiningRules.Upgrade.SPEED?Material.SUGAR:Material.DIAMOND,label,()->{
                    if(capped)return;
                    try{var result=economy.buy(player.getUniqueId(),upgrade,wallet(player));items.give(player,store.profile(player.getUniqueId()));
                        message(player,switch(result.outcome()){case SUCCESS->"&a강화 성공 &f+"+result.gain()+(upgrade==MiningRules.Upgrade.SPEED?"%p":"");case FAILURE->"&7강화 실패";case DESTROYED->"&c곡괭이 파괴 &7강화 초기화";});
                        MiningItems.sound(player,switch(result.outcome()){case SUCCESS->"block.anvil.use";case FAILURE->"block.note_block.bass";case DESTROYED->"entity.item.break";},1f);openUpgrade(player);}
                    catch(IOException | RuntimeException failure){fail(player,failure);}
                },lore);
            }
            button(menu,22,Material.ARROW,"뒤로",()->openShop(player));player.openInventory(menu.inventory);
        } catch(IOException | RuntimeException failure){fail(player,failure);}
    }
    @EventHandler(priority=EventPriority.LOWEST) public void joined(PlayerJoinEvent e){recover(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST) public void quit(PlayerQuitEvent e) {
        Player player=e.getPlayer();if(!owns(player))return;UUID id=player.getUniqueId();transitioning.add(id);
        try{recovery.restore(player,true);active.remove(id);blocked.remove(id);}
        catch(IOException | RuntimeException failure){blocked.add(id);plugin.getLogger().log(Level.SEVERE,"Mining quit inventory recovery retained for "+id,failure);}
        finally{transitioning.remove(id);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void mine(BlockBreakEvent e) {
        Player player=e.getPlayer();if(!owns(player) && !inWorld(e.getBlock().getLocation()))return;
        e.setCancelled(true);e.setDropItems(false);e.setExpToDrop(0);
        if(!eligible(player) || worldFailed || !cave.ready() || !inWorld(e.getBlock().getLocation()) || player.getGameMode()!=GameMode.SURVIVAL || player.getInventory().getHeldItemSlot()!=4)return;
        var b=e.getBlock();var cell=new CaveLayout.Cell(b.getX(),b.getY(),b.getZ());String material=cave.material(cell);
        if(material==null || material.equals("AIR") || !material.equals(b.getType().name()))return;
        try {
            var p=store.profile(player.getUniqueId());if(p.pending()!=null || !items.owned(player.getInventory().getItemInMainHand(),player.getUniqueId(),p.tool()))return;
            var ore=MiningRules.Ore.fromBlock(material);int amount=ore==null?0:MiningRules.drops(ore,p.fortune(),java.util.concurrent.ThreadLocalRandom.current());
            if(ore!=null&&!items.canCarry(p,ore,amount)){message(player,"&c인벤토리 공간 부족");return;}
            if(store.mine(player.getUniqueId(),cell,material,ore,amount)){cave.mined(cell);if(ore!=null)items.cargo(player,store.profile(player.getUniqueId()));}
        } catch(IOException | RuntimeException failure){blocked.add(player.getUniqueId());fail(player,failure);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void click(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player player))return;
        if(e.getView().getTopInventory().getHolder() instanceof Menu menu) {
            e.setCancelled(true);
            if(menu.inventory!=e.getView().getTopInventory() || !menu.owner.equals(player.getUniqueId()) || !eligible(player) || e.getClickedInventory()!=menu.inventory || !e.isLeftClick() || e.isShiftClick())return;
            var action=menu.actions.get(e.getRawSlot());if(action!=null && !menu.consumed){menu.consumed=true;menuActions.addLast(()->{
                if(eligible(player) && player.getOpenInventory().getTopInventory()==menu.inventory)try{MiningItems.sound(player,"ui.button.click",1.1f);action.run();}catch(RuntimeException failure){fail(player,failure);}
            });}return;
        }
        if(owns(player) || inWorld(player.getLocation())) {
            // Parent-owned chest GUIs may run their own top-slot actions; no inventory transfer is allowed.
            e.setCancelled(true);
        } else if(items!=null && (items.managed(e.getCurrentItem()) || items.managed(e.getCursor())))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p && (owns(p) || items.managed(e.getOldCursor())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e) {
        if(!(e.getPlayer() instanceof Player p) || !owns(p))return;
        Inventory inv=e.getInventory();
        if(inv.getHolder() instanceof org.bukkit.block.BlockState || inv.getHolder() instanceof org.bukkit.block.DoubleChest
                || inv.getHolder() instanceof Entity entity && !(entity instanceof Player))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(owns(e.getPlayer()) || items.managed(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p && (owns(p) || items.managed(e.getItem().getItemStack())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(owns(e.getPlayer()) || items.managed(e.getMainHandItem()) || items.managed(e.getOffHandItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void durability(PlayerItemDamageEvent e){if(owns(e.getPlayer()) || items.managed(e.getItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void craft(CraftItemEvent e){if(e.getWhoClicked() instanceof Player p && owns(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) {
        Player player=e.getPlayer();if(!owns(player) && !inWorld(player.getLocation()))return;
        if(e.getAction().isRightClick()&&e.getClickedBlock()!=null) {
            e.setUseInteractedBlock(Event.Result.DENY);
            var b=e.getClickedBlock();if(e.getHand()==EquipmentSlot.HAND && e.getAction()==Action.RIGHT_CLICK_BLOCK && b.getType()==Material.LODESTONE && inWorld(b.getLocation()))returnToHub(player);
        }
        // Navigation item use belongs to the Host; only pickaxe use is denied here.
        if(e.getAction().isRightClick()&&items.marked(e.getItem()))e.setUseItemInHand(Event.Result.DENY);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void npc(PlayerInteractEntityEvent e) {
        if(!inWorld(e.getRightClicked().getLocation()) && !owns(e.getPlayer()))return;e.setCancelled(true);
        if(e.getHand()==EquipmentSlot.HAND && e.getRightClicked().getScoreboardTags().contains(MiningNpc.TAG))openShop(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void npcAt(PlayerInteractAtEntityEvent e){npc(e);}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(owns(e.getPlayer()) || inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucket(PlayerBucketEmptyEvent e){if(owns(e.getPlayer()) || inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucket(PlayerBucketFillEvent e){if(owns(e.getPlayer()) || inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(inWorld(e.getEntity().getLocation()) || e.getEntity() instanceof Player p && owns(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void hunger(FoodLevelChangeEvent e){if(e.getEntity() instanceof Player p && owns(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){if(owns(e.getEntity())){e.setKeepInventory(true);e.setKeepLevel(true);e.getDrops().clear();e.setDroppedExp(0);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){if(active.contains(e.getPlayer().getUniqueId()))e.setRespawnLocation(cave.spawn());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e) {
        if(!active.contains(e.getPlayer().getUniqueId()) || transitioning.contains(e.getPlayer().getUniqueId()) || e.getTo()==null)return;
        if(!cave.contains(e.getTo()))e.setTo(cave.spawn());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e) {
        Player p=e.getPlayer();UUID id=p.getUniqueId();if(transitioning.contains(id))return;
        if(owns(p) && !inWorld(e.getTo()))try{leave(p,true);}catch(RuntimeException failure){e.setCancelled(true);}
        else if(!owns(p) && inWorld(e.getTo()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void mode(PlayerGameModeChangeEvent e){if(owns(e.getPlayer()) && !transitioning.contains(e.getPlayer().getUniqueId()) && e.getNewGameMode()!=GameMode.SURVIVAL)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void blockExplosion(BlockExplodeEvent e){if(inWorld(e.getBlock().getLocation())){e.blockList().clear();e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityExplosion(EntityExplodeEvent e){if(inWorld(e.getLocation())){e.blockList().clear();e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityBlock(EntityChangeBlockEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonExtendEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonRetractEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void flow(BlockFromToEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void burn(BlockBurnEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void ignite(BlockIgniteEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void spread(BlockSpreadEvent e){if(inWorld(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void portal(PortalCreateEvent e){if(cave!=null && cave.world.equals(e.getWorld()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void spawn(CreatureSpawnEvent e){if(inWorld(e.getLocation()) && !e.getEntity().getScoreboardTags().contains(MiningNpc.TAG))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void itemSpawn(ItemSpawnEvent e){if(inWorld(e.getLocation()) || items.managed(e.getEntity().getItemStack()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void hopper(InventoryMoveItemEvent e){if(items.managed(e.getItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void hopper(InventoryPickupItemEvent e){if(inWorld(e.getItem().getLocation()) || items.managed(e.getItem().getItemStack()))e.setCancelled(true);}
    private boolean inWorld(Location location){return cave!=null && cave.belongs(location);}
    private void fail(Player player,Throwable failure){
        if(failure instanceof IllegalArgumentException){message(player,failure.getMessage());return;}
        plugin.getLogger().log(Level.SEVERE,"Mining operation failed for "+player.getUniqueId()+"; durable journal retained",failure);
        message(player,"광산 저장을 확인하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
    private static void message(Player player,String message){player.sendMessage(MiningItems.text(message==null?"처리하지 못했습니다.":message));}
    private static void mainThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Mining must run on the server thread");}
    @Override public void close() {
        mainThread();if(closed)return;closed=true;if(task!=null)task.cancel();menuActions.clear();
        var recoveries=new HashSet<>(active);recoveries.addAll(blocked);
        for(UUID id:recoveries) {
            Player player=Bukkit.getPlayer(id);if(player==null)continue;transitioning.add(id);
            try{recovery.restore(player,true);active.remove(id);blocked.remove(id);}
            catch(IOException | RuntimeException failure){plugin.getLogger().log(Level.SEVERE,"Mining shutdown recovery retained for "+id,failure);}
            finally{transitioning.remove(id);}
        }
        HandlerList.unregisterAll(this);if(npc!=null)npc.close();if(cave!=null)cave.close();
        if(store!=null)try{store.close();}catch(IOException failure){plugin.getLogger().log(Level.SEVERE,"Mining database close failed",failure);}
    }
}
