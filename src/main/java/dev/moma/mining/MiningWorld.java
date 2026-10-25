package dev.moma.mining;

import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** All block writes are bounded on the server thread. The durable cell map is authoritative. */
final class MiningWorld implements AutoCloseable {
    private final JavaPlugin plugin;
    private final MiningStore store;
    private final Map<CaveLayout.Cell,String> cells;
    private final ArrayDeque<CaveLayout.Cell> edits=new ArrayDeque<>();
    private final Random random=new Random();
    private final int budget;
    private final double threshold;
    final World world;
    private boolean initialBuild,ready;
    private int initial,remaining;
    private final Set<Long> tickets=new HashSet<>();
    MiningWorld(JavaPlugin plugin,MiningStore store)throws IOException {
        this.plugin=plugin;this.store=store;
        budget=Math.clamp(plugin.getConfig().getInt("mining.edits-per-tick",2048),256,4096);
        double configured=plugin.getConfig().getDouble("mining.refill-threshold",.60);
        threshold=Double.isFinite(configured)?Math.clamp(configured,.05,.75):.60;
        String name=plugin.getConfig().getString("mining.world","moma_mining");
        if(name==null || !name.matches("moma_mining(?:_[a-z0-9_-]{1,20})?"))throw new IOException("Mining requires its own moma_mining world");
        var key=new NamespacedKey(plugin,"mining_world");
        World loaded=Bukkit.getWorld(name);
        if(loaded!=null && !loaded.getPersistentDataContainer().has(key,PersistentDataType.BYTE))throw new IOException("Refusing an unowned loaded world");
        world=Objects.requireNonNull(Bukkit.createWorld(new WorldCreator(name).generator(new EmptyGenerator())),"Mining world creation failed");
        String bound=store.meta("world-uuid");
        if(bound!=null && !bound.equals(world.getUID().toString()))throw new IOException("Mining world UUID changed; preserve the original world");
        store.meta("world-uuid",world.getUID().toString());
        world.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        world.getWorldBorder().setCenter(.5,.5);world.getWorldBorder().setSize(CaveLayout.RADIUS*2+1);
        world.setSpawnLocation(spawn());
        world.setGameRule(GameRules.SPAWN_MOBS,false);world.setGameRule(GameRules.ADVANCE_TIME,false);
        world.setGameRule(GameRules.ADVANCE_WEATHER,false);world.setGameRule(GameRules.RANDOM_TICK_SPEED,0);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,0);
        world.setTime(6000);world.setStorm(false);world.setThundering(false);world.setAutoSave(true);
        if(!CaveLayout.VERSION.equals(store.meta("layout-version"))){
            cells=new LinkedHashMap<>();
            for(var c:CaveLayout.cells())cells.put(c,"STONE");
            cells.putAll(CaveLayout.veins(cells.keySet(),random,.22));
            store.replaceTerrain(cells,countOres(),CaveLayout.VERSION);
        }else{
            cells=store.cells();if(cells.size()!=CaveLayout.cells().size())throw new IOException("Incomplete mining cell ledger");
        }
        remaining=countOres();
        try { initial=Integer.parseInt(Objects.requireNonNull(store.meta("initial-ores"))); }
        catch(RuntimeException e) { throw new IOException("Invalid mining generation counter",e); }
        if(initial<1)throw new IOException("Mining generation contains no ore");
        initialBuild=!world.getUID().toString().equals(store.meta("shell-built"));
        if(initialBuild) {
            for(int x=-CaveLayout.RADIUS;x<=CaveLayout.RADIUS;x++)for(int z=-CaveLayout.RADIUS;z<=CaveLayout.RADIUS;z++)
                for(int y=CaveLayout.FLOOR;y<=CaveLayout.ROOF;y++)edits.add(new CaveLayout.Cell(x,y,z));
        } else edits.addAll(cells.keySet());
        MiningResetCycle.initialize(this,store,threshold,this::refill).counts(remaining,initial);
    }
    boolean ready(){return ready;}
    Location spawn(){return new Location(world,.5,CaveLayout.HUB_FLOOR+1,-19.5,0,0);}
    boolean belongs(Location l){return l!=null && world.equals(l.getWorld());}
    boolean contains(Location l){return belongs(l) && Math.abs(l.getX())<CaveLayout.RADIUS && Math.abs(l.getZ())<CaveLayout.RADIUS && l.getY()>=CaveLayout.FLOOR+1 && l.getY()<CaveLayout.ROOF;}
    String material(CaveLayout.Cell c){return cells.get(c);}
    private boolean occupied(CaveLayout.Cell c) {
        var box=new BoundingBox(c.x()-.15,c.y()-.15,c.z()-.15,c.x()+1.15,c.y()+1.15,c.z()+1.15);
        for(Player player:world.getPlayers())if(player.getBoundingBox().overlaps(box))return true;
        return false;
    }
    void tick()throws IOException {
        for(int i=0;i<budget && !edits.isEmpty();i++) {
            var c=edits.removeFirst();
            int chunkX=c.x()>>4,chunkZ=c.z()>>4;long packed=((long)chunkX<<32)^(chunkZ&0xffffffffL);
            if(tickets.add(packed))world.addPluginChunkTicket(chunkX,chunkZ,plugin);
            String desired=cells.getOrDefault(c,CaveLayout.shell(c));
            var block=world.getBlockAt(c.x(),c.y(),c.z());
            String structure=CaveLayout.blockData(c);
            if(block.getType().name().equals(desired)){
                if(structure!=null&&!block.getBlockData().getAsString().equals(Bukkit.createBlockData(structure).getAsString()))block.setBlockData(Bukkit.createBlockData(structure),false);
                continue;
            }
            if(!desired.equals("AIR") && occupied(c)) {
                if(cells.containsKey(c)) {
                    store.replaceCells(Map.of(c,"AIR"),initial);
                    String old=cells.put(c,"AIR");if(MiningRules.Ore.fromBlock(old)!=null)remaining--;
                } else { edits.addLast(c); break; }
                continue;
            }
            block.setType(Material.valueOf(desired),false);
            if(structure!=null)block.setBlockData(Bukkit.createBlockData(structure),false);
        }
        if(!ready && edits.isEmpty()) {
            if(initialBuild){world.save();store.meta("shell-built",world.getUID().toString());initialBuild=false;}
            ready=true;
        }
        var cycle=MiningResetCycle.current(this);cycle.counts(remaining,initial);
        if(ready && cycle.shouldReset())refill();
    }
    private int countOres(){return (int)cells.values().stream().filter(s->MiningRules.Ore.fromBlock(s)!=null).count();}
    private void refill()throws IOException {
        var next=CaveLayout.refill(cells,this::occupied,random);
        var changes=new LinkedHashMap<CaveLayout.Cell,String>();
        next.forEach((c,m)->{if(!m.equals(cells.get(c)))changes.put(c,m);});
        int count=(int)next.values().stream().filter(s->MiningRules.Ore.fromBlock(s)!=null).count();
        // Persist the complete new desired generation before changing visible blocks.
        store.replaceCells(changes,count);cells.putAll(changes);initial=count;remaining=count;
        edits.addAll(changes.keySet());ready=edits.isEmpty();
        var cycle=MiningResetCycle.current(this);cycle.counts(remaining,initial);cycle.scheduleNext();
    }
    void mined(CaveLayout.Cell c){String old=cells.put(c,"AIR");if(MiningRules.Ore.fromBlock(old)!=null)remaining--;world.getBlockAt(c.x(),c.y(),c.z()).setType(Material.AIR,false);var cycle=MiningResetCycle.current(this);cycle.counts(remaining,initial);}
    @Override public void close(){MiningResetCycle.close(this);for(long p:tickets)world.removePluginChunkTicket((int)(p>>32),(int)p,plugin);tickets.clear();}
    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise(){return false;}
        @Override public boolean shouldGenerateSurface(){return false;}
        @Override public boolean shouldGenerateBedrock(){return false;}
        @Override public boolean shouldGenerateCaves(){return false;}
        @Override public boolean shouldGenerateDecorations(){return false;}
        @Override public boolean shouldGenerateMobs(){return false;}
        @Override public boolean shouldGenerateStructures(){return false;}
    }
}
