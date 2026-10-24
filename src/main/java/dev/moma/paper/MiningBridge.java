package dev.moma.paper;

import dev.moma.mining.MiningGame;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Keeps mine inventory handoffs and shared Noqui services outside the mine implementation. */
final class MiningBridge implements MiningGame.Host, Listener {
    private static final NamespacedKey KEY=new NamespacedKey("momadefense","mine_navigation");
    private final GameService games;
    private final Lobby lobby;
    private final SettingsMenu settings;
    private final TraitMenu traits;
    private MinigameMenu menu;
    MiningBridge(GameService games,Lobby lobby,SettingsMenu settings,TraitMenu traits){
        this.games=games;this.lobby=lobby;this.settings=settings;this.traits=traits;
    }
    void menu(MinigameMenu menu){this.menu=menu;}
    @Override public long balance(Player player){return games.noqui.starlight(player.getUniqueId());}
    @Override public boolean spend(Player player,String transaction,long amount){return games.noqui.spendStarlight(player.getUniqueId(),transaction,amount);}
    @Override public void reward(Player player,String transaction,long amount){
        if(!games.noqui.rewardStarlight(player.getUniqueId(),transaction,amount))throw new IllegalStateException("별빛 지갑이 준비되지 않았습니다.");
    }
    @Override public void prepareEntry(Player player){
        if(games.playing(player))throw new IllegalArgumentException("진행 중인 게임을 먼저 종료하세요.");
        if(games.watching(player))games.requestLeave(player);
        games.noqui.prepareDefense(player);
        games.tools.restore(player);
        games.noqui.prepareItems(player);
        if(games.bgm!=null)games.bgm.stop(player);
    }
    @Override public void giveTools(Player player){
        set(player,0,Material.COMPASS,"sessions","&b미니게임");
        set(player,1,Material.COMPARATOR,"settings","&e환경설정");
        set(player,2,Material.ENCHANTED_BOOK,"traits","&d특성 선택");
        set(player,7,Material.LEATHER_CHESTPLATE,"wardrobe","&d치장");
        set(player,8,Material.RED_BED,"leave","&c돌아가기");
        refresh(player);games.noqui.defenseEntered(player);
    }
    private static void set(Player player,int slot,Material material,String id,String name){
        var item=Ui.item(material,name);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,id);item.setItemMeta(meta);
        player.getInventory().setItem(slot,item);
    }
    void refresh(Player player){
        var inventory=player.getInventory();var old=inventory.getItem(3);
        if(!games.noqui.started()){if("noqui".equals(id(old)))inventory.setItem(3,null);return;}
        int amount=(int)Math.clamp(games.noqui.ticketCount(player.getUniqueId()),1,64);
        if("noqui".equals(id(old))&&old.getAmount()==amount)return;
        set(player,3,Material.PAPER,"noqui","&b노래퀴즈");inventory.getItem(3).setAmount(amount);
    }
    private static String id(ItemStack item){return item==null||!item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(KEY,PersistentDataType.STRING);}
    @Override public void openSharedShop(Player player){games.noqui.openSharedShop(player);}
    @Override public void openMineShop(Player player,Runnable sales,Runnable upgrade){games.noqui.openMiningShop(player,sales,upgrade);}
    @Override public void returnToHub(Player player){
        if(games.mining!=null&&games.mining.owns(player))games.mining.leave(player,true);
        games.noqui.defenseFailed(player);
        if(!games.noqui.routeJoin(player)&&lobby!=null){lobby.send(player);games.refreshNoquiTool(player);}
    }
    @EventHandler public void interact(PlayerInteractEvent event){
        Player player=event.getPlayer();
        if(event.getHand()!=EquipmentSlot.HAND||!event.getAction().isRightClick()||games.mining==null||!games.mining.owns(player))return;
        String id=id(event.getItem());if(id==null)return;
        event.setCancelled(true);
        switch(id){
            case "sessions"->menu.open(player);
            case "settings"->settings.open(player);
            case "traits"->traits.open(player);
            case "wardrobe"->games.noqui.openWardrobe(player);
            case "noqui"->{if(games.noqui.started())games.noqui.openProxy(player);}
            case "leave"->returnToHub(player);
            default->{}
        }
    }
}
