package dev.moma.mining;

import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.inventory.ItemStack;
import java.util.*;

final class MiningNpc implements AutoCloseable {
    static final String TAG="moma_mining_shop_v1";
    private final MiningWorld cave;
    private final List<Mannequin> miners=new ArrayList<>();
    MiningNpc(MiningWorld cave){this.cave=cave;}
    void tick() {
        if(!cave.ready())return;
        if(miners.size()!=CaveLayout.SHOPS.size()||miners.stream().anyMatch(n->!n.isValid())) {
            close();
            for(var old:cave.world.getEntitiesByClass(Mannequin.class))if(old.getScoreboardTags().contains(TAG))old.remove();
            for(var station:CaveLayout.SHOPS){
            var at=new Location(cave.world,station.x()+.5,station.y(),station.z()+.5,90,0);
            var npc=cave.world.spawn(at,Mannequin.class,e->{e.addScoreboardTag(TAG);e.setPersistent(true);});
            npc.setImmovable(true);npc.setGravity(false);npc.setInvulnerable(true);npc.setCollidable(false);npc.setSilent(true);
            npc.setProfile(ResolvableProfile.resolvableProfile().uuid(UUID.nameUUIDFromBytes(("moma-miner:"+station).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .skinPatch(p->p.body(Key.key("minecraft:entity/player/wide/steve")).model(PlayerTextures.SkinModel.CLASSIC)).build());
            npc.setSkinParts(SkinParts.allParts());npc.customName(MiningItems.text("&e광부"));npc.setCustomNameVisible(true);npc.setDescription(null);
            var equipment=npc.getEquipment();equipment.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
            equipment.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));equipment.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
            equipment.setBoots(new ItemStack(Material.IRON_BOOTS));equipment.setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
            miners.add(npc);
            }
        }
        for(var npc:miners)face(npc);
    }
    private void face(Mannequin npc){
        Player nearest=null;double distance=49;
        for(Player player:cave.world.getPlayers()) {
            if(!player.isOnline() || player.isDead() || player.getGameMode()==GameMode.SPECTATOR)continue;
            double candidate=player.getLocation().distanceSquared(npc.getLocation());
            if(candidate<=distance && (nearest==null || candidate<distance || player.getUniqueId().compareTo(nearest.getUniqueId())<0)){nearest=player;distance=candidate;}
        }
        if(nearest!=null) {
            var direction=nearest.getEyeLocation().toVector().subtract(npc.getEyeLocation().toVector());
            if(direction.lengthSquared()>1e-8){var look=npc.getLocation().setDirection(direction);npc.setRotation(look.getYaw(),look.getPitch());}
        }
    }
    @Override public void close(){for(var npc:miners)npc.remove();miners.clear();}
}
