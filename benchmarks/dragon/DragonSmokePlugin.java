package dev.moma.benchmark;

import dev.moma.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Localhost-only packet and authoritative-movement regression fixture. */
public final class DragonSmokePlugin extends JavaPlugin {
    private Object adapter,map;
    private Arena arena;
    private UUID dragon,wolf,control;
    private int ticks;
    private Player owner,viewer;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        Bukkit.getScheduler().runTaskTimer(this,()->{try{tick();}catch(Throwable error){
            getLogger().log(java.util.logging.Level.SEVERE,"DRAGON_SMOKE_FAILED",error);Bukkit.shutdown();
        }},1,1);
    }
    private void tick()throws Exception {
        if(arena==null) {
            owner=Bukkit.getPlayerExact("DragonOwner");viewer=Bukkit.getPlayerExact("DragonViewer");
            if(owner==null || viewer==null)return;
            Object games=field(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),"games");
            Object maps=field(games,"maps");adapter=field(games,"entities");
            map=call(maps,"create","dragon",6);
            World world=(World)call(map,"world");
            Location entrance=(Location)call(map,"entrance");
            for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)
                world.getChunkAt((entrance.getBlockX()>>4)+x,(entrance.getBlockZ()>>4)+z).addPluginChunkTicket(this);
            for(Player p:List.of(owner,viewer)){p.setGameMode(GameMode.ADVENTURE);p.teleport(entrance.clone().add(0,8,0));p.setAllowFlight(true);p.setFlying(true);}
            arena=new Arena("dragon",owner.getUniqueId(),new Grid(6),30,100);
            return;
        }
        ticks++;
        if(ticks==40) {
            dragon=(UUID)call(adapter,"spawnEnemy",map,owner.getUniqueId(),EnemyType.ENDER_DRAGON,true);
            UUID zombie=(UUID)call(adapter,"spawnEnemy",map,owner.getUniqueId(),EnemyType.ZOMBIE,false);
            for(var entry:Map.of(dragon,EnemyType.ENDER_DRAGON,zombie,EnemyType.ZOMBIE).entrySet())
                arena.addEnemy(new dev.moma.core.Enemy(entry.getKey(),arena.id(),entry.getValue(),1e9,20,0,true));
            wolf=(UUID)call(adapter,"spawnDefender",map,owner.getUniqueId(),UnitType.WOLF,Rarity.COMMON,new Cell(2,2));
            World world=(World)call(map,"world");
            control=world.spawn(((Location)call(map,"entrance")).add(8,8,8),EnderDragon.class,d->{
                d.setAI(false);d.setGravity(false);d.setNoPhysics(true);d.setSilent(true);d.setInvulnerable(true);
            }).getUniqueId();
            Files.writeString(Path.of("dragon-ids.json"),"{\"dragon\":"+Bukkit.getEntity(dragon).getEntityId()
                    +",\"zombie\":"+Bukkit.getEntity(zombie).getEntityId()+",\"wolf\":"+Bukkit.getEntity(wolf).getEntityId()
                    +",\"control\":"+Bukkit.getEntity(control).getEntityId()+"}");
        }
        if(ticks<40)return;
        if(ticks==50)call(adapter,"selectGlow",owner,wolf);
        if(ticks==80)for(Player p:List.of(owner,viewer))p.hideEntity(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),Bukkit.getEntity(dragon));
        if(ticks==85)for(Player p:List.of(owner,viewer))p.showEntity(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),Bukkit.getEntity(dragon));
        if(ticks==100)((Mob)Bukkit.getEntity(dragon)).setLeftHanded(true);
        if(ticks==150){Field bridge=adapter.getClass().getDeclaredField("batchBridge");bridge.setAccessible(true);bridge.set(adapter,null);}
        new CombatEngine().tick(arena,ticks);
        if(!(boolean)call(adapter,"advanceAll",arena,map))throw new AssertionError("Movement failed");
        for(var enemy:arena.activeEnemies()) {
            LivingEntity entity=(LivingEntity)Bukkit.getEntity(enemy.entityId());
            Location expected=(Location)call(map,"location",enemy.position(arena.grid().route()));
            if(entity==null || entity.hasAI() || entity.getLocation().distanceSquared(expected)>1e-6)
                throw new AssertionError("Server authority changed: "+enemy.type());
        }
        if(ticks==260) {
            for(var enemy:arena.activeEnemies())call(adapter,"remove",enemy.entityId());
            call(adapter,"remove",wolf);Bukkit.getEntity(control).remove();
            Files.writeString(Path.of("dragon-server-pass.json"),"{\"ticks\":221,\"batchAndSingleMotion\":true,\"serverAiDisabled\":true,\"retracking\":true,\"metadataChanges\":true}");
            Bukkit.getScheduler().cancelTasks(this);
            Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20);
        }
    }
    private static Object field(Object object,String name)throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(var method:object.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.setAccessible(true);return method.invoke(object,args);
        }throw new NoSuchMethodException(name);
    }
}
