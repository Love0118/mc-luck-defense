package dev.moma.benchmark;

import dev.moma.core.*;
import dev.moma.core.Enemy;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Localhost-only fixture validating every enemy appearance on the real server. */
public final class BiomeSmokePlugin extends JavaPlugin {
    private Object adapter,map;
    private Arena arena;
    private int ticks;
    private int preparation;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        Bukkit.getScheduler().runTaskTimer(this,()->{try{tick();}catch(Throwable error){
            getLogger().log(java.util.logging.Level.SEVERE,"BIOME_SMOKE_FAILED",error);Bukkit.shutdown();
        }},1,1);
    }
    private void tick() throws Exception {
        if(map==null) {
            var plugin=Bukkit.getPluginManager().getPlugin("MCLuckDefense");Object games=field(plugin,"games"),maps=field(games,"maps");
            adapter=field(games,"entities");map=call(maps,"get","biomes");
            if(map==null)map=call(maps,"create","biomes",6);
            World world=(World)call(map,"world");int x=(int)call(map,"originX"),z=(int)call(map,"originZ");
            for(int cx=(x-16)>>4;cx<=(x+48)>>4;cx++)for(int cz=(z-16)>>4;cz<=(z+48)>>4;cz++)world.getChunkAt(cx,cz).addPluginChunkTicket(this);
        }
        if(preparation++<40)return;
        if(arena==null) {
            var plugin=Bukkit.getPluginManager().getPlugin("MCLuckDefense");Object games=field(plugin,"games"),maps=field(games,"maps");
            adapter=field(games,"entities");map=call(maps,"get","biomes");
            if(map==null)map=call(maps,"create","biomes",6);
            arena=new Arena("biomes",UUID.randomUUID(),new Grid(6),0,100);
            for(WaveTheme theme:WaveTheme.all())
                if(Registry.BIOME.get(NamespacedKey.minecraft(theme.biome()))==null)throw new AssertionError("Missing biome "+theme.biome());
            Set<String> registered=new HashSet<>();Registry.BIOME.forEach(b->registered.add(b.getKey().getKey()));
            Set<String> used=new HashSet<>();WaveTheme.all().forEach(t->used.add(t.biome()));
            if(!registered.equals(used))throw new AssertionError("Biome coverage differs: "+registered+" vs "+used);
            for(EnemyType type:EnemyType.values()) {
                UUID id=(UUID)call(adapter,"spawnEnemy",map,arena.owner(),type,true);
                arena.addEnemy(new Enemy(id,arena.id(),type,1e9,10,1,true));
                LivingEntity entity=(LivingEntity)Bukkit.getEntity(id);
                if(entity==null || !(boolean)call(adapter,"managed",entity) || entity.hasAI() || !entity.isInvulnerable())throw new AssertionError(type);
                if(entity instanceof EnderDragon dragon)for(ComplexEntityPart part:dragon.getParts())
                    if(!(boolean)call(adapter,"managed",part))throw new AssertionError("Dragon part unprotected");
            }
            return;
        }
        new CombatEngine(false).tick(arena,++ticks);
        if(!(boolean)call(adapter,"advanceAll",arena,map)) {
            for(Enemy enemy:arena.activeEnemies()) {Entity entity=Bukkit.getEntity(enemy.entityId());
                if(entity==null || !entity.isValid())throw new AssertionError("Entity lost "+enemy.type()+" at "+ticks);}
            throw new AssertionError("Motion rejected at "+ticks);
        }
        for(Enemy enemy:arena.activeEnemies()) {
            Entity entity=Bukkit.getEntity(enemy.entityId());Location expected=(Location)call(map,"location",enemy.position(arena.grid().route()));
            if(entity==null || entity.getLocation().distanceSquared(expected)>1e-6)throw new AssertionError("Position mismatch "+enemy.type()+" expected="+expected+" actual="+(entity==null?null:entity.getLocation()));
        }
        if(ticks==220) {
            for(Enemy enemy:arena.activeEnemies())call(adapter,"remove",enemy.entityId());
            for(Enemy enemy:arena.activeEnemies())if(Bukkit.getEntity(enemy.entityId())!=null)throw new AssertionError("Cleanup "+enemy.type());
            Files.writeString(Path.of("biome-smoke.json"),"{\"enemyTypes\":"+EnemyType.values().length+",\"biomes\":67,\"ticks\":220,\"spawnMoveRemove\":true,\"dragonPartsProtected\":true}");
            getLogger().info("BIOME_SMOKE_PASSED");Bukkit.shutdown();
        }
    }
    private static Object field(Object object,String name)throws Exception {var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(Method method:object.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.setAccessible(true);return method.invoke(object,args);
        }throw new NoSuchMethodException(name);
    }
}
