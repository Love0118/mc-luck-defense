package dev.moma.paper;

import dev.moma.core.*;
import java.io.Serializable;
import java.util.*;
import org.bukkit.*;

/** Classloader-neutral serialization payload: no Bukkit entity, world, listener or task references. */
final class SessionState {
    record Position(UUID world,double x,double y,double z,float yaw,float pitch) implements Serializable {
        static Position of(Location at){return new Position(at.getWorld().getUID(),at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());}
        Location location(){return new Location(Objects.requireNonNull(Bukkit.getWorld(world),"복원할 월드가 없습니다."),x,y,z,yaw,pitch);}
    }
    record Session(UUID id,String map,UUID world,int x,int y,int z,Arena arena,Campaign campaign,HashRandom random,
                   Position returnLocation,String returnMode,boolean returnFlight,boolean returnFlying,
                   boolean assisted,int announcedRound,long simulationTick,int speed,EnumSet<Rarity> autoSell,
                   boolean autoPlacement,boolean layoutDirty,boolean bulkBuying,int bulkPurchases,String bgmTrack) implements Serializable {}
    record Watch(UUID player,UUID session,Position returnLocation,String returnMode,boolean returnFlight,boolean returnFlying) implements Serializable {}
    record Game(long tick,List<Session> sessions,List<Watch> spectators,BgmService.Saved bgm) implements Serializable {
        Game combatOnly(){return new Game(tick,sessions,spectators,null);}
    }
    private SessionState() {}
}
