package dev.moma.mining;

import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.profile.PlayerTextures;

final class MiningNpc implements AutoCloseable {
    static final String TAG="moma_mining_shop_v1";
    private final MiningWorld cave;
    private Mannequin npc;
    MiningNpc(MiningWorld cave){this.cave=cave;}
    void tick() {
        if(!cave.ready())return;
        if(npc==null || !npc.isValid()) {
            var at=new Location(cave.world,4.5,CaveLayout.HUB_FLOOR+1,-29.5,90,0);
            var existing=cave.world.getEntitiesByClass(Mannequin.class).stream().filter(e->e.getScoreboardTags().contains(TAG)).toList();
            npc=existing.isEmpty()?cave.world.spawn(at,Mannequin.class,e->{e.addScoreboardTag(TAG);e.setPersistent(true);}):existing.getFirst();
            for(int i=1;i<existing.size();i++)existing.get(i).remove();
            npc.setImmovable(true);npc.setGravity(false);npc.setInvulnerable(true);npc.setCollidable(false);npc.setSilent(true);
            npc.setProfile(ResolvableProfile.resolvableProfile().uuid(java.util.UUID.fromString("7d5243d5-c30e-4bdd-a039-71ec1ad10002"))
                    .skinPatch(p->p.body(Key.key("noqui:entity/shopkeeper")).model(PlayerTextures.SkinModel.SLIM)).build());
            npc.setSkinParts(SkinParts.allParts());npc.customName(MiningItems.text("상점"));npc.setCustomNameVisible(true);npc.setDescription(null);
            if(npc.getLocation().distanceSquared(at)>.01)npc.teleport(at);
        }
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
    @Override public void close(){if(npc!=null)npc.remove();npc=null;}
}
