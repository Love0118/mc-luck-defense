package dev.moma.mining;

import java.util.EnumMap;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Physical ore layout; the mining profile remains the authority for quantities. */
final class CargoStacks {
    private static final NamespacedKey KEY=new NamespacedKey("momadefense","mining_cargo");
    static MiningRules.Ore ore(ItemStack item){
        if(item==null||item.isEmpty())return null;
        String value=item.getPersistentDataContainer().get(KEY,PersistentDataType.STRING);
        if(value==null)return null;
        try{return MiningRules.Ore.valueOf(value);}catch(IllegalArgumentException invalid){return null;}
    }
    static ItemStack stack(MiningRules.Ore ore,int amount){
        var item=MiningItems.icon(MiningItems.material(ore),"&f"+ore.label,"&7광산 채굴 광물");item.setAmount(amount);
        var meta=item.getItemMeta();meta.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,ore.name());item.setItemMeta(meta);return item;
    }
    static int capacity(Player player,MiningRules.Ore ore,boolean mining){
        int count=0;var contents=player.getInventory().getStorageContents();
        for(int slot=0;slot<contents.length;slot++){
            if(mining&&MiningItems.reservedSlot(slot))continue;
            var item=contents[slot];
            if(item==null||item.isEmpty())count+=64;
            else if(ore(item)==ore)count+=Math.max(0,64-item.getAmount());
        }
        var offhand=player.getInventory().getItemInOffHand();
        if(ore(offhand)==ore)count+=Math.max(0,64-offhand.getAmount());
        return count;
    }
    static void reconcile(Player player,MiningStore.Profile profile,boolean mining){
        var inv=player.getInventory();var remaining=new EnumMap<MiningRules.Ore,Long>(MiningRules.Ore.class);remaining.putAll(profile.cargo());
        // Retain moved/split stacks, including the cursor and offhand, before delivering additions.
        for(int slot=-1;slot<inv.getSize();slot++){
            var item=slot<0?player.getItemOnCursor():inv.getItem(slot);var ore=ore(item);if(ore==null)continue;
            int keep=(int)Math.min(item.getAmount(),remaining.getOrDefault(ore,0L));remaining.merge(ore,-(long)keep,Long::sum);
            if(keep==item.getAmount())continue;
            if(keep>0)item.setAmount(keep);else item=null;
            if(slot<0)player.setItemOnCursor(item);else inv.setItem(slot,item);
        }
        for(int slot=0;slot<inv.getSize();slot++){
            if(slot>=36&&slot!=40||mining&&MiningItems.reservedSlot(slot))continue;
            var item=inv.getItem(slot);var ore=ore(item);if(ore==null)continue;
            int add=(int)Math.min(Math.max(0,64-item.getAmount()),remaining.getOrDefault(ore,0L));
            if(add>0){item.setAmount(item.getAmount()+add);inv.setItem(slot,item);remaining.merge(ore,-(long)add,Long::sum);}
        }
        for(var ore:MiningRules.Ore.values())for(int slot=0;slot<36&&remaining.getOrDefault(ore,0L)>0;slot++){
            if(mining&&MiningItems.reservedSlot(slot))continue;
            var existing=inv.getItem(slot);if(existing!=null&&!existing.isEmpty())continue;
            int amount=(int)Math.min(64,remaining.get(ore));inv.setItem(slot,stack(ore,amount));remaining.merge(ore,-(long)amount,Long::sum);
        }
    }
}
