package dev.moma.mining;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class MiningItems {
    private static final Set<Integer> RESERVED=Set.of(0,1,2,3,4,7,8);
    static boolean reservedSlot(int slot){return RESERVED.contains(slot);}
    private static final NamespacedKey NAVIGATION=new NamespacedKey("momadefense","mine_navigation");
    private static final NamespacedKey CARGO=new NamespacedKey("momadefense","mining_cargo");
    private final NamespacedKey owner,serial;
    MiningItems(JavaPlugin plugin){owner=new NamespacedKey(plugin,"mining_owner");serial=new NamespacedKey(plugin,"mining_tool");}
    static Component text(String label){return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(label).decoration(TextDecoration.ITALIC,false);}
    static void sound(Player player,String sound,float pitch){player.playSound(player.getLocation(),"minecraft:"+sound,SoundCategory.MASTER,.5f,pitch);}
    static ItemStack icon(Material type,String label,String... lore) {
        var item=new ItemStack(type);var meta=item.getItemMeta();meta.displayName(text(label));
        meta.lore(Arrays.stream(lore).map(MiningItems::text).toList());item.setItemMeta(meta);return item;
    }
    boolean marked(ItemStack item){return item!=null && !item.isEmpty() && item.getPersistentDataContainer().has(serial,PersistentDataType.STRING);}
    boolean managed(ItemStack item){return marked(item) || item!=null && !item.isEmpty() && item.getPersistentDataContainer().has(NAVIGATION,PersistentDataType.STRING);}
    boolean owned(ItemStack item,UUID id,UUID tool) {
        return marked(item) && item.getType()==Material.IRON_PICKAXE && item.getAmount()==1
                && id.toString().equals(item.getPersistentDataContainer().get(owner,PersistentDataType.STRING))
                && tool.toString().equals(item.getPersistentDataContainer().get(serial,PersistentDataType.STRING));
    }
    ItemStack pickaxe(UUID id,MiningStore.Profile p) {
        var item=icon(Material.IRON_PICKAXE,"&b광산 곡괭이","&7채굴 속도 &b"+p.speed()+"%","&7행운 &d"+p.fortune(),"&8철 곡괭이 기준");
        var meta=item.getItemMeta();meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(owner,PersistentDataType.STRING,id.toString());
        meta.getPersistentDataContainer().set(serial,PersistentDataType.STRING,p.tool().toString());
        var tool=meta.getTool();tool.setDamagePerBlock(0);tool.setDefaultMiningSpeed(1);
        tool.setRules(List.of());
        var materials=new ArrayList<Material>();materials.add(Material.STONE);
        for(var ore:MiningRules.Ore.values())materials.add(Material.valueOf(ore.block()));
        tool.addRule(materials,MiningRules.speed(p.speed()),true);meta.setTool(tool);
        if(p.fortune()>0)meta.addEnchant(Enchantment.FORTUNE,p.fortune(),false);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);item.setItemMeta(meta);return item;
    }
    void give(Player player,MiningStore.Profile p) {
        var inv=player.getInventory();var at=inv.getItem(4);
        if(at!=null && !at.isEmpty() && !marked(at))throw new IllegalStateException("Mining pickaxe slot is occupied");
        remove(player);inv.setItem(4,pickaxe(player.getUniqueId(),p));inv.setHeldItemSlot(4);
    }
    void remove(Player player) {
        var inv=player.getInventory();for(int i=0;i<inv.getSize();i++)if(marked(inv.getItem(i)))inv.setItem(i,null);
        if(marked(player.getItemOnCursor()))player.setItemOnCursor(null);
    }
    /** Plan the tool overlay without overwriting carried items if storage is full. */
    void workspace(Player player){
        var inventory=player.getInventory();var next=inventory.getStorageContents().clone();var displaced=new ArrayList<ItemStack>();
        for(int slot:RESERVED)if(next[slot]!=null&&!next[slot].isEmpty()){displaced.add(next[slot]);next[slot]=null;}
        for(var item:displaced){
            int free=-1;for(int slot=0;slot<next.length;slot++)if(!RESERVED.contains(slot)&&(next[slot]==null||next[slot].isEmpty())){free=slot;break;}
            if(free<0)throw new IllegalArgumentException("공용 인벤토리 공간이 부족합니다. 아이템을 정리한 뒤 입장하세요.");next[free]=item;
        }
        inventory.setStorageContents(next);
    }
    private static boolean cargoItem(ItemStack item){return item!=null&&!item.isEmpty()&&item.getPersistentDataContainer().has(CARGO,PersistentDataType.STRING);}
    private int cargoSpace(Player player){
        int space=0;boolean mining=marked(player.getInventory().getItem(4));var contents=player.getInventory().getStorageContents();
        for(int slot=0;slot<contents.length;slot++)if((!mining||!RESERVED.contains(slot))&&(contents[slot]==null||contents[slot].isEmpty()||cargoItem(contents[slot])))space++;
        if(cargoItem(player.getInventory().getItemInOffHand()))space++;
        if(cargoItem(player.getItemOnCursor()))space++;
        return space;
    }
    boolean canCarry(Player player,MiningStore.Profile p,MiningRules.Ore ore,int amount){return cargoSlots(p.credit(ore,amount).cargo())<=cargoSpace(player)&&CargoStacks.capacity(player,ore,marked(player.getInventory().getItem(4)))>=amount;}
    private static long cargoSlots(Map<MiningRules.Ore,Long> cargo){long slots=0;for(long count:cargo.values())slots=Math.addExact(slots,(count+63)/64);return slots;}
    static Material material(MiningRules.Ore ore){return Material.valueOf(switch(ore){case COPPER->"RAW_COPPER";case IRON->"RAW_IRON";case GOLD->"RAW_GOLD";case LAPIS->"LAPIS_LAZULI";default->ore.name();});}
    void cargo(Player player,MiningStore.Profile p){
        CargoStacks.reconcile(player,p,marked(player.getInventory().getItem(4)));
    }
}
