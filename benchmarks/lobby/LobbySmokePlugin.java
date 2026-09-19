package dev.moma.benchmark;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs only on an isolated localhost server with three protocol clients. */
public final class LobbySmokePlugin extends JavaPlugin {
    private List<String> samples;
    private int sample, stage, waitTicks;
    private Object games, maps;
    private Player first, second, viewer;
    private Object firstSession, secondSession;
    private int facedTypes, checkedAttackDirections;
    private int selectedEntityId, glowClearEntityId;
    @Override public void onEnable() {
        if (!Bukkit.getIp().equals("127.0.0.1")) throw new IllegalStateException("Localhost only");
        try {
            samples=Files.readAllLines(Path.of("block-samples.tsv"));
            var plugin=Bukkit.getPluginManager().getPlugin("MCLuckDefense");
            games=field(plugin,"games"); maps=field(games,"maps");
            if (call(maps,"get","smoke1")==null) call(maps,"create","smoke1",6);
            if (call(maps,"get","smoke2")==null) call(maps,"create","smoke2",6);
        } catch (Exception error) { throw new RuntimeException(error); }
        Bukkit.getScheduler().runTaskTimer(this,()-> {
            try { tick(); } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE,"LOBBY_SMOKE_FAILED",error); Bukkit.shutdown();
            }
        },1,1);
    }
    private void tick() throws Exception {
        World world=Objects.requireNonNull(Bukkit.getWorld("mud_lobby"));
        if (sample<samples.size()) {
            for (int limit=Math.min(sample+8,samples.size());sample<limit;sample++) {
                String[] row=samples.get(sample).split("\t");
                var actual=world.getBlockAt(Integer.parseInt(row[0]),Integer.parseInt(row[1]),Integer.parseInt(row[2])).getBlockData();
                var expected=Bukkit.createBlockData(row[3]);
                require(actual.matches(expected),"Block mismatch at "+samples.get(sample)+" actual="+actual.getAsString());
            }
            return;
        }
        if (stage==0) {
            first=Bukkit.getPlayerExact("MudBench00"); second=Bukkit.getPlayerExact("MudBench01"); viewer=Bukkit.getPlayerExact("MudBench02");
            if (first==null || second==null || viewer==null || !first.isOnline() || !second.isOnline() || !viewer.isOnline()) return;
            require(first.getWorld().equals(world)&&second.getWorld().equals(world),"Login must enter lobby");
            require(first.getLocation().distance(world.getSpawnLocation())<1,"Login must use configured spawn");
            call(games,"start",first); call(games,"start",second);
            firstSession=call(games,"session",first); secondSession=call(games,"session",second);
            require(firstSession!=secondSession,"Distinct session objects");
            require(!arena(firstSession).id().equals(arena(secondSession).id()),"Distinct arenas");
            call(games,"summon",first);
            require(arena(firstSession).defenderCount()==1,"Real summon");
            call(games,"start",viewer);
            require(call(games,"session",viewer)!=null,"Dynamic arena allocation");
            call(games,"leave",viewer);
            // Existing two arenas were full; a third must have been persisted.
            require(((Collection<?>)call(maps,"all")).size()>=3,"Third arena created");
            call(games,"spectate",viewer,first.getName());
            require((boolean)call(games,"watching",viewer),"Spectator registered");
            require(call(games,"session",viewer)==null,"Spectator has no combat session");
            require(viewer.getGameMode()==GameMode.SPECTATOR,"Spectator mode");
            Object adapter=field(games,"entities"),map=field(firstSession,"map");
            verifyAllBodies(adapter,map);
            arena(firstSession).credit(100);
            UUID[] common=new UUID[2];
            for(int i=0;i<2;i++) {
                final int index=i;
                arena(firstSession).summon(first.getUniqueId(),new SummonRoll(UnitType.SKELETON,Rarity.COMMON),(t,r,c)->{
                    try { return common[index]=(UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),t,r,c); }
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }
            long coins=arena(firstSession).coins();
            long commonCount=arena(firstSession).activeDefenders().stream().filter(d->d.rarity()==Rarity.COMMON).count();
            call(games,"sellRarity",first,Rarity.COMMON);
            require(arena(firstSession).coins()==coins+commonCount*3,"Bulk sale payout");
            for(UUID id:common) require(Bukkit.getEntity(id)==null,"Bulk sale entity removal");
            // All roles produce real combat effects while the spectator is present.
            for(UnitType type:List.of(UnitType.WOLF,UnitType.IRON_GOLEM,UnitType.SKELETON,UnitType.WITCH,UnitType.BLAZE,UnitType.EVOKER)) {
                arena(firstSession).summon(first.getUniqueId(),new SummonRoll(type,Rarity.MYTHIC),(t,r,c)->{
                    try{return (UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),t,r,c);}
                    catch(Exception e){throw new RuntimeException(e);}
                });
            }
            call(games,"spawnEnemies",first,EnemyType.ZOMBIE,10,false);
            Defender selected=arena(firstSession).defenders().getFirst();
            selectedEntityId=Bukkit.getEntity(selected.entityId()).getEntityId();
            call(games,"select",first,selected.entityId());
            require(!Bukkit.getEntity(selected.entityId()).isGlowing(),"Selection must never change shared glow state");
            stage=1;return;
        }
        if (stage==1) {
            verifyAttackFacing(firstSession);
            for(Enemy enemy:arena(firstSession).activeEnemies()) {
                var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(enemy.entityId());
                require(entity.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue()==2,"Enemy scale");
                double length=arena(firstSession).grid().route().length();int side=(int)((enemy.progress()%length)/(length/4));
                float expected=new float[]{-90,0,90,-180}[side];
                require(Math.abs(Location.normalizeYaw(entity.getYaw()-expected))<.001,"Enemy route direction");
                require(Math.abs(Location.normalizeYaw(entity.getBodyYaw()-expected))<.001,"Enemy body direction");
            }
            if(waitTicks==40) {
                var unit=Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId());
                unit.setInvisible(true); // Outbound metadata must retain the owner's glow override.
            }
            if(waitTicks==60) Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()).setInvisible(false);
            if(waitTicks==80) {
                Defender unit=arena(firstSession).defenders().getLast();glowClearEntityId=Bukkit.getEntity(unit.entityId()).getEntityId();
                call(games,"select",first,unit.entityId());
            }
            if(waitTicks==100) first.hideEntity(this,Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()));
            if(waitTicks==110) first.showEntity(this,Bukkit.getEntity(arena(firstSession).selected().orElseThrow().entityId()));
            if (++waitTicks<340) return;
            require(arena(secondSession).enemyCount()>0,"Waves must spawn actual enemies");
            arena(firstSession).finish(Arena.Outcome.ENEMY_LIMIT); stage=2;return;
        }
        if (stage==2) {
            require(call(games,"session",first)==null,"Defeat must release session");
            require(first.getWorld().equals(world),"Defeat must return to lobby");
            require(viewer.getWorld().equals(world) && !(boolean)call(games,"watching",viewer),"Spectator auto return");
            require(call(games,"session",second)==secondSession,"Other session must continue");
            for (Defender unit:arena(firstSession).activeDefenders()) require(Bukkit.getEntity(unit.entityId())==null,"Defender cleanup");
            for (Enemy enemy:arena(firstSession).activeEnemies()) require(Bukkit.getEntity(enemy.entityId())==null,"Enemy cleanup");
            call(games,"start",first); Object restarted=call(games,"session",first);
            require(restarted!=firstSession && arena(restarted).id().equals(arena(firstSession).id()),"Arena reuse with new state");
            require(arena(restarted).coins()==CampaignRules.standard().startingCoins(),"Fresh funds");
            arena(restarted).finish(Arena.Outcome.TIME_LIMIT); arena(secondSession).finish(Arena.Outcome.VICTORY);
            stage=3;return;
        }
        if (stage==3) {
            require(call(games,"session",first)==null && call(games,"session",second)==null,"Terminal cleanup");
            require(first.getWorld().equals(world)&&second.getWorld().equals(world),"Both back to lobby");
            require(facedTypes==24 && checkedAttackDirections>0,"Actual entity facing must be exercised");
            Files.writeString(Path.of("lobby-smoke-passed.json"),"{\"blockStates\":"+samples.size()+",\"clients\":3,\"sessionIsolation\":true,\"defeatReturn\":true,\"slotReuse\":true,\"victoryReturn\":true,\"dynamicArena\":true,\"spectatorReturn\":true,\"bulkSale\":true,\"facedMobTypes\":"+facedTypes+",\"actualAttackDirections\":"+checkedAttackDirections+",\"selectedEntityId\":"+selectedEntityId+",\"secondSelectedEntityId\":"+glowClearEntityId+"}");
            getLogger().info("LOBBY_SMOKE_PASSED"); stage=4; Bukkit.shutdown();
        }
    }
    private void verifyAllBodies(Object adapter,Object map) throws Exception {
        for(UnitType type:UnitType.values()) {
            UUID id=(UUID)call(adapter,"spawnDefender",map,first.getUniqueId(),type,Rarity.COMMON,new Cell(0,0));
            Defender defender=new Defender(id,first.getUniqueId(),arena(firstSession).id(),type,Rarity.COMMON,new Cell(0,0));
            var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(id);
            require(entity.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue()==2,"Defender scale "+type);
            call(adapter,"face",defender,new Point(3,0));
            require((boolean)call(adapter,"moveDefender",id,call(map,"location",defender.position())),"Anchor "+type);
            require(Math.abs(entity.getYaw()+90)<.001 && Math.abs(entity.getBodyYaw()+90)<.001,"Facing survives anchor "+type);
            Location moved=(Location)call(map,"location",new Point(3,3));
            require((boolean)call(adapter,"moveDefender",id,moved),"Manual move "+type);
            require(Math.abs(entity.getYaw()+90)<.001,"Facing survives movement "+type);
            call(adapter,"remove",id);facedTypes++;
        }
    }
    private void verifyAttackFacing(Object session) throws Exception {
        var attacks=(Map<?,?>)field(field(session,"attackEffects"),"attacks");
        for(var attack:attacks.entrySet()) {
            Defender defender=(Defender)attack.getKey();Point primary=(Point)((List<?>)attack.getValue()).getFirst();
            Point origin=defender.position();if(origin.equals(primary))continue;
            float yaw=Location.normalizeYaw((float)Math.toDegrees(Math.atan2(-(primary.x()-origin.x()),primary.z()-origin.z())));
            var entity=(org.bukkit.entity.LivingEntity)Bukkit.getEntity(defender.entityId());
            require(entity!=null && Math.abs(Location.normalizeYaw(entity.getYaw()-yaw))<.001,"Attack target head direction");
            require(Math.abs(Location.normalizeYaw(entity.getBodyYaw()-yaw))<.001,"Attack target body direction");
            checkedAttackDirections++;
        }
    }
    private static Arena arena(Object session) throws Exception { return (Arena)field(session,"arena"); }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object target,String name) throws Exception { Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target); }
    private static Object call(Object target,String name,Object...args) throws Exception {
        for (Method m:target.getClass().getDeclaredMethods()) {
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
            Class<?>[] types=m.getParameterTypes();boolean matches=true;
            for(int i=0;i<args.length;i++) {
                Class<?> type=types[i]==int.class?Integer.class:types[i]==boolean.class?Boolean.class:types[i];
                if(args[i]!=null&&!type.isInstance(args[i]))matches=false;
            }
            if(matches){m.setAccessible(true);return m.invoke(target,args);}
        }
        throw new NoSuchMethodException(name);
    }
}
