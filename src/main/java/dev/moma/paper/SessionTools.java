package dev.moma.paper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Session hotbar tools with a player-persistent backup of the two replaced slots. */
final class SessionTools {
    private final NamespacedKey toolKey, backupKey, heldKey;
    SessionTools(MomaPlugin plugin) {
        toolKey=new NamespacedKey(plugin,"session_tool");
        backupKey=new NamespacedKey(plugin,"session_hotbar_backup");
        heldKey=new NamespacedKey(plugin,"session_held_slot");
    }
    void give(Player player) {
        give(player, false);
    }
    void giveViewer(Player player) { give(player, true); }
    private void give(Player player, boolean viewer) {
        restore(player);
        var inventory=player.getInventory(); var data=player.getPersistentDataContainer();
        data.set(backupKey,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(new ItemStack[]{inventory.getItem(0),inventory.getItem(1),inventory.getItem(8)}));
        data.set(heldKey,PersistentDataType.INTEGER,inventory.getHeldItemSlot());
        if (!viewer) {
        inventory.setItem(0,tool(Material.BLAZE_ROD,"move","&b포탑 선택·이동","&7좌클릭: 아군 선택 → 빈 배치 칸으로 이동"));
        inventory.setItem(1,tool(Material.EMERALD,"sell","&6선택 포탑 판매","&7포탑 선택 후 이 아이템으로 우클릭","&c전설 이상 판매 불가"));
        }
        inventory.setItem(8,tool(Material.RED_BED,"leave","&c세션 나가기","&7우클릭: 로비로 돌아가기"));
        inventory.setHeldItemSlot(viewer ? 8 : 0);
    }
    private ItemStack tool(Material material,String id,String name,String... lore) {
        ItemStack item=Ui.item(material,name,lore);var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey,PersistentDataType.STRING,id);item.setItemMeta(meta);return item;
    }
    boolean holding(Player player,String id) {
        int slot=switch(id) { case "move" -> 0; case "sell" -> 1; case "leave" -> 8; default -> -1; };
        return player.getInventory().getHeldItemSlot()==slot && id.equals(id(player.getInventory().getItemInMainHand()));
    }
    boolean isTool(ItemStack item) { return id(item)!=null; }
    private String id(ItemStack item) {
        return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(toolKey,PersistentDataType.STRING);
    }
    void restore(Player player) {
        var data=player.getPersistentDataContainer(); var inventory=player.getInventory();
        byte[] bytes=data.get(backupKey,PersistentDataType.BYTE_ARRAY);
        ItemStack[] previous=bytes==null?null:ItemStack.deserializeItemsFromBytes(bytes);
        if(previous!=null && previous.length!=2 && previous.length!=3)throw new IllegalStateException("Invalid session hotbar backup for "+player.getUniqueId());
        for(int slot=0;slot<inventory.getSize();slot++)if(isTool(inventory.getItem(slot)))inventory.setItem(slot,null);
        if(previous!=null) {
            inventory.setItem(0,previous[0]);inventory.setItem(1,previous[1]);
            if(previous.length==3)inventory.setItem(8,previous[2]);
            int held=data.getOrDefault(heldKey,PersistentDataType.INTEGER,0);
            inventory.setHeldItemSlot(Math.max(0,Math.min(8,held)));
        }
        data.remove(backupKey);data.remove(heldKey);
    }
}
