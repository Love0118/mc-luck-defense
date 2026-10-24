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
    boolean managed(ItemStack item){return marked(item) || item!=null && !item.isEmpty() && (item.getPersistentDataContainer().has(NAVIGATION,PersistentDataType.STRING)||item.getPersistentDataContainer().has(CARGO,PersistentDataType.STRING));}
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
    boolean canCarry(MiningStore.Profile p,MiningRules.Ore ore,int amount){return cargoSlots(p.credit(ore,amount).cargo())<=27;}
    private static long cargoSlots(Map<MiningRules.Ore,Long> cargo){long slots=0;for(long count:cargo.values())slots=Math.addExact(slots,(count+63)/64);return slots;}
    void cargo(Player player,MiningStore.Profile p){
        var inv=player.getInventory();for(int slot=9;slot<36;slot++)inv.setItem(slot,null);
        int slot=9;
        for(var ore:MiningRules.Ore.values()){
            long remaining=p.cargo().getOrDefault(ore,0L);
            while(remaining>0&&slot<36){
                Material material=Material.valueOf(switch(ore){case COPPER->"RAW_COPPER";case IRON->"RAW_IRON";case GOLD->"RAW_GOLD";case LAPIS->"LAPIS_LAZULI";default->ore.name();});
                var item=icon(material,"&f"+ore.label,"&7광산 채굴 광물");item.setAmount((int)Math.min(64,remaining));
                var meta=item.getItemMeta();meta.getPersistentDataContainer().set(CARGO,PersistentDataType.STRING,ore.name());item.setItemMeta(meta);inv.setItem(slot++,item);remaining-=item.getAmount();
            }
        }
    }
}
