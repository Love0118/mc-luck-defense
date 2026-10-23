package dev.moma.paper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.Arrays;

/** Session hotbar tools with a player-persistent backup of replaced slots. */
final class SessionTools {
    static final int NOQUI_SLOT=3,NOQUI_LOBBY_SLOT=2;
    private static final NamespacedKey NOQUI_COUNT=new NamespacedKey("momadefense","noqui_ticket_count");
    private static final NamespacedKey SOUND_LEVEL = new NamespacedKey("momadefense", "attack_volume");
    private static final int[] VOLUMES = {100, 50, 25, 0};
    private void giveSettings(Player player) {
        player.getInventory().setItem(5,tool(Material.COMPARATOR,"summon_alerts","&e환경설정",
                "&7우클릭: 환경설정"));
    }
    static float soundVolume(Player player) {
        var data=player.getPersistentDataContainer();
        if(data==null) return 1f;
        Integer level=data.get(SOUND_LEVEL,PersistentDataType.INTEGER);
        return VOLUMES[level==null ? 0 : Math.clamp(level,0,3)] / 100f;
    }
    void cycleSound(Player player) {
        var data=player.getPersistentDataContainer();
        int level=data.getOrDefault(SOUND_LEVEL,PersistentDataType.INTEGER,0);
        data.set(SOUND_LEVEL,PersistentDataType.INTEGER,(Math.clamp(level,0,3)+1)%4);
        giveSound(player);
    }
    private void giveSound(Player player) {
        int percent=Math.round(soundVolume(player)*100);
        player.getInventory().setItem(7,tool(Material.NOTE_BLOCK,"sound","&b몹 소리 &7· &e"+(percent==0 ? "음소거" : percent+"%"),
                "&7우클릭: 100% → 50% → 25% → 음소거"));
    }
    private final NamespacedKey toolKey, backupKey, heldKey, noquiBackupKey,noquiSlotKey;
    SessionTools(MomaPlugin plugin) {
        toolKey=new NamespacedKey(plugin,"session_tool");
        backupKey=new NamespacedKey(plugin,"session_hotbar_backup");
        heldKey=new NamespacedKey(plugin,"session_held_slot");
        noquiBackupKey=new NamespacedKey(plugin,"session_noqui_backup");
        noquiSlotKey=new NamespacedKey(plugin,"session_noqui_slot");
    }
    void give(Player player) {
        give(player, false, false);
    }
    void giveViewer(Player player) { give(player, true, false); }
    void giveLobby(Player player) { give(player, false, true); }
    void refreshActive(Player player,boolean viewer) {
        var inventory=player.getInventory();var data=player.getPersistentDataContainer();
        ItemStack[] previous=ItemStack.deserializeItemsFromBytes(data.get(backupKey,PersistentDataType.BYTE_ARRAY));
        if(previous.length==6) {
            ItemStack[] expanded=Arrays.copyOf(previous,7);expanded[6]=inventory.getItem(5);
            data.set(backupKey,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(expanded));
        } else if(previous.length!=7)throw new IllegalStateException("Invalid session hotbar backup for "+player.getUniqueId());
        if(!viewer) {
            inventory.setItem(1,tool(Material.BLAZE_ROD,"move","&b포탑 선택·이동","&7좌클릭: 선택 → 빈 칸 이동 / 다른 기물과 교환","&7같은 기물 다시 클릭·다른 아이템: 선택 해제"));
            inventory.setItem(2,tool(Material.EMERALD,"sell","&6선택 포탑 판매","&7좌클릭: 포탑 선택 · 우클릭: 판매","&7다른 아이템을 들면 선택 해제","&7진 태초·미라클: 수동판매"));
        }
        giveSettings(player);
    }
    void downgrade(Player player,boolean playing) {
        updateNoqui(player,false,false,0);
        var data=player.getPersistentDataContainer();byte[] bytes=data.get(backupKey,PersistentDataType.BYTE_ARRAY);
        if(bytes==null)return;
        ItemStack[] previous=ItemStack.deserializeItemsFromBytes(bytes);
        if(previous.length!=7)return;
        var inventory=player.getInventory();
        inventory.setItem(5,previous[6]);
        if(playing) {
            inventory.setItem(1,tool(Material.BLAZE_ROD,"move","&b포탑 선택·이동","&7좌클릭: 선택 → 빈 칸 이동 / 다른 기물과 교환","&7같은 기물 다시 클릭: 선택 해제"));
            inventory.setItem(2,tool(Material.EMERALD,"sell","&6선택 포탑 판매","&7포탑 선택 후 이 아이템으로 우클릭","&7진 태초·미라클: 수동판매"));
        }
        data.set(backupKey,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(Arrays.copyOf(previous,6)));
    }
    private void give(Player player, boolean viewer, boolean lobby) {
        restore(player);
        var inventory=player.getInventory(); var data=player.getPersistentDataContainer();
        data.set(backupKey,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(new ItemStack[]{inventory.getItem(0),inventory.getItem(1),inventory.getItem(8),inventory.getItem(7),inventory.getItem(6),inventory.getItem(2),inventory.getItem(5)}));
        data.set(heldKey,PersistentDataType.INTEGER,inventory.getHeldItemSlot());
        if (!viewer && !lobby) {
        inventory.setItem(0,tool(Material.NETHER_STAR,"manage","&e세션 관리","&7우클릭: 소환·판매·배속 메뉴","&7F키로도 열 수 있습니다."));
        inventory.setItem(1,tool(Material.BLAZE_ROD,"move","&b포탑 선택·이동","&7좌클릭: 선택 → 빈 칸 이동 / 다른 기물과 교환","&7같은 기물 다시 클릭·다른 아이템: 선택 해제"));
        inventory.setItem(2,tool(Material.EMERALD,"sell","&6선택 포탑 판매","&7좌클릭: 포탑 선택 · 우클릭: 판매","&7다른 아이템을 들면 선택 해제","&7진 태초·미라클: 수동판매"));
        }
        if (lobby || viewer) inventory.setItem(0,tool(Material.COMPASS,"sessions","&b미니게임","&7우클릭: 미니게임 메뉴"));
        if (lobby) inventory.setItem(1,tool(Material.ENCHANTED_BOOK,"traits","&d특성 선택","&7우클릭: 특성 장착·해제","&7최고 100·250·500라운드에 슬롯 해금"));
        if (lobby) inventory.setItem(8,tool(Material.RED_BED,"portal_return","&c포탈섭으로 돌아가기","&7우클릭: 포탈섭으로 이동"));
        else inventory.setItem(8,tool(Material.RED_BED,"leave","&c세션 나가기","&7우클릭: 로비로 돌아가기"));
        if (!lobby) giveSound(player);
        giveSettings(player);
        if (!lobby) {
            boolean enabled=data.getOrDefault(new NamespacedKey("momadefense","bgm_muted"),PersistentDataType.BYTE,(byte)0)==0;
            updateBgm(player,viewer,enabled);
        }
        inventory.setHeldItemSlot(viewer ? 8 : 0);
    }
    private ItemStack tool(Material material,String id,String name,String... lore) {
        ItemStack item=Ui.item(material,name,lore);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey,PersistentDataType.STRING,id);item.setItemMeta(meta);return item;
    }
    void updateBgm(Player player,boolean viewer,boolean enabled) {
        player.getInventory().setItem(6,tool(Material.JUKEBOX,"bgm", viewer ? "&bBGM · "+(enabled?"&aON":"&7OFF") : "&bBGM 관리",
                viewer ? "&7우클릭: BGM 켜기·끄기" : "&7우클릭: 노래 선택·업로드"));
    }
    void updateNoqui(Player player,boolean show,boolean playing,long tickets) {
        var data=player.getPersistentDataContainer();var inventory=player.getInventory();
        byte[] bytes=data.get(noquiBackupKey,PersistentDataType.BYTE_ARRAY);
        int previousSlot=data.getOrDefault(noquiSlotKey,PersistentDataType.INTEGER,4),slot=playing?NOQUI_SLOT:NOQUI_LOBBY_SLOT;
        if(bytes!=null&&(previousSlot<0||previousSlot>=inventory.getSize()))throw new IllegalStateException("Invalid Noqui proxy slot");
        if(show) {
            if(bytes!=null&&previousSlot!=slot){updateNoqui(player,false,false,0);bytes=null;}
            if(bytes==null) {
                var previous=inventory.getItem(slot);
                data.set(noquiBackupKey,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(new ItemStack[]{"noqui_return".equals(id(previous))?null:previous}));
                data.set(noquiSlotKey,PersistentDataType.INTEGER,slot);
            }
            tickets=Math.max(0,tickets);var current=inventory.getItem(slot);int amount=(int)Math.max(1,Math.min(64,tickets));
            if(!"noqui_return".equals(id(current))||current.getAmount()!=amount||current.getItemMeta().getPersistentDataContainer().getOrDefault(NOQUI_COUNT,PersistentDataType.LONG,-1L)!=tickets) {
                var proxy=tool(Material.PAPER,"noqui_return","&b노래퀴즈","&7출제권 "+tickets+"장","&7우클릭: 로비 · 출제");proxy.setAmount(amount);
                var meta=proxy.getItemMeta();meta.getPersistentDataContainer().set(NOQUI_COUNT,PersistentDataType.LONG,tickets);proxy.setItemMeta(meta);inventory.setItem(slot,proxy);
            }
            return;
        }
        ItemStack[] previous=bytes==null?null:ItemStack.deserializeItemsFromBytes(bytes);
        if(previous!=null && previous.length!=1)throw new IllegalStateException("Invalid Noqui hotbar backup for "+player.getUniqueId());
        for(int index=0;index<inventory.getSize();index++)if("noqui_return".equals(id(inventory.getItem(index))))inventory.setItem(index,null);
        if("noqui_return".equals(id(player.getItemOnCursor())))player.setItemOnCursor(null);
        if(previous!=null)inventory.setItem(previousSlot,previous[0]);
        data.remove(noquiBackupKey);data.remove(noquiSlotKey);
    }
    boolean holding(Player player,String id) {
        int slot=switch(id) { case "manage", "sessions" -> 0; case "move", "traits" -> 1; case "sell" -> 2; case "noqui_return" -> player.getPersistentDataContainer().getOrDefault(noquiSlotKey,PersistentDataType.INTEGER,4); case "summon_alerts" -> 5; case "bgm" -> 6; case "sound" -> 7; case "leave", "portal_return" -> 8; default -> -1; };
        var inventory=player.getInventory();
        return inventory!=null && inventory.getHeldItemSlot()==slot && id.equals(id(inventory.getItemInMainHand()));
    }
    boolean isTool(ItemStack item) { return id(item)!=null; }
    private String id(ItemStack item) {
        return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(toolKey,PersistentDataType.STRING);
    }
    void restore(Player player) {
        updateNoqui(player,false,false,0);
        var data=player.getPersistentDataContainer(); var inventory=player.getInventory();
        byte[] bytes=data.get(backupKey,PersistentDataType.BYTE_ARRAY);
        ItemStack[] previous=bytes==null?null:ItemStack.deserializeItemsFromBytes(bytes);
        if(previous!=null && (previous.length<2 || previous.length>7))throw new IllegalStateException("Invalid session hotbar backup for "+player.getUniqueId());
        for(int slot=0;slot<inventory.getSize();slot++)if(isTool(inventory.getItem(slot)))inventory.setItem(slot,null);
        if(previous!=null) {
            inventory.setItem(0,previous[0]);inventory.setItem(1,previous[1]);
            if(previous.length>=3)inventory.setItem(8,previous[2]);
            if(previous.length>=4)inventory.setItem(7,previous[3]);
            if(previous.length>=5)inventory.setItem(6,previous[4]);
            if(previous.length>=6)inventory.setItem(2,previous[5]);
            if(previous.length>=7)inventory.setItem(5,previous[6]);
            int held=data.getOrDefault(heldKey,PersistentDataType.INTEGER,0);
            inventory.setHeldItemSlot(Math.max(0,Math.min(8,held)));
        }
        data.remove(backupKey);data.remove(heldKey);
    }
}
