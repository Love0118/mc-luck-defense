package dev.moma.paper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.Arrays;

/** Session hotbar tools with a player-persistent backup of replaced slots. */
final class SessionTools {
    private static final NamespacedKey SOUND_LEVEL = new NamespacedKey("momadefense", "attack_volume");
    private static final NamespacedKey OTHER_SUMMON_ALERTS = new NamespacedKey("momadefense", "other_summon_alerts");
    private static final int[] VOLUMES = {100, 50, 25, 0};
    static boolean otherSummonAlerts(Player player) {
        var data=player.getPersistentDataContainer();
        return data==null || data.getOrDefault(OTHER_SUMMON_ALERTS,PersistentDataType.BYTE,(byte)1)!=0;
    }
    void toggleSummonAlerts(Player player) {
        var data=player.getPersistentDataContainer();
        data.set(OTHER_SUMMON_ALERTS,PersistentDataType.BYTE,otherSummonAlerts(player)?(byte)0:(byte)1);
        giveSummonAlerts(player);
    }
    private void giveSummonAlerts(Player player) {
        player.getInventory().setItem(5,tool(Material.PAPER,"summon_alerts","&e다른 세션 소환 알림 &7· "
                +(otherSummonAlerts(player)?"&aON":"&7OFF"),"&7우클릭: 알림과 소리 켜기·끄기"));
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
    private final NamespacedKey toolKey, backupKey, heldKey;
    SessionTools(MomaPlugin plugin) {
        toolKey=new NamespacedKey(plugin,"session_tool");
        backupKey=new NamespacedKey(plugin,"session_hotbar_backup");
        heldKey=new NamespacedKey(plugin,"session_held_slot");
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
        giveSummonAlerts(player);
    }
    void downgrade(Player player,boolean playing) {
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
        if (lobby || viewer) inventory.setItem(0,tool(Material.COMPASS,"sessions","&b게임 세션 보기","&7우클릭: 게임 참가·관전 메뉴"));
        if (lobby) inventory.setItem(1,tool(Material.ENCHANTED_BOOK,"traits","&d특성 선택","&7우클릭: 특성 장착·해제","&7최고 100·250·500라운드에 슬롯 해금"));
        if (!lobby) inventory.setItem(8,tool(Material.RED_BED,"leave","&c세션 나가기","&7우클릭: 로비로 돌아가기"));
        if (!lobby) giveSound(player);
        giveSummonAlerts(player);
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
    boolean holding(Player player,String id) {
        int slot=switch(id) { case "manage", "sessions" -> 0; case "move", "traits" -> 1; case "sell" -> 2; case "summon_alerts" -> 5; case "bgm" -> 6; case "sound" -> 7; case "leave" -> 8; default -> -1; };
        var inventory=player.getInventory();
        return inventory!=null && inventory.getHeldItemSlot()==slot && id.equals(id(inventory.getItemInMainHand()));
    }
    boolean isTool(ItemStack item) { return id(item)!=null; }
    private String id(ItemStack item) {
        return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(toolKey,PersistentDataType.STRING);
    }
    void restore(Player player) {
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
