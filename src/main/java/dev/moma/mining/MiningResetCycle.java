package dev.moma.mining;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

/** One persisted wall-clock deadline drives both regeneration and the mine-only boss bar. */
final class MiningResetCycle implements AutoCloseable {
    static final long INTERVAL_MILLIS=1_200_000;
    @FunctionalInterface interface Reset {void regenerate()throws IOException;}
    record Snapshot(int remainingOre,int initialOre,double threshold,long remainingMillis,boolean ready) {}
    private static final String DEADLINE="next-refill-at-ms";
    private static final Map<MiningWorld,MiningResetCycle> CYCLES=new IdentityHashMap<>();
    private final MiningStore store;
    private final LongSupplier clock;
    private final MiningWorld world;
    private final double threshold;
    private final Reset reset;
    private final Map<UUID,Player> viewers=new HashMap<>();
    private final BossBar bar=BossBar.bossBar(MiningItems.text("광산 리셋 20:00"),1,BossBar.Color.WHITE,BossBar.Overlay.PROGRESS);
    private long deadline;
    private int remainingOre,initialOre;
    private Runnable onClose=()->{};

    MiningResetCycle(MiningWorld world,MiningStore store,LongSupplier clock,double threshold,Reset reset)throws IOException{
        this.world=world;this.store=store;this.clock=clock;this.threshold=threshold;this.reset=reset;
        String saved=store.meta(DEADLINE);
        try{deadline=saved==null?Math.addExact(clock.getAsLong(),INTERVAL_MILLIS):Long.parseLong(saved);}
        catch(RuntimeException invalid){throw new IOException("Invalid mining reset deadline",invalid);}
        if(saved==null)store.meta(DEADLINE,Long.toString(deadline));
    }
    static MiningResetCycle initialize(MiningWorld world,MiningStore store,double threshold,Reset reset)throws IOException{
        var cycle=CYCLES.get(world);
        if(cycle==null){cycle=new MiningResetCycle(world,store,System::currentTimeMillis,threshold,reset);CYCLES.put(world,cycle);}return cycle;
    }
    static MiningResetCycle current(MiningWorld world){return Objects.requireNonNull(CYCLES.get(world),"Mining reset cycle not initialized");}
    void counts(int remaining,int initial){remainingOre=remaining;initialOre=initial;}
    Snapshot snapshot(){return new Snapshot(remainingOre,initialOre,threshold,remaining(),world.ready());}
    boolean shouldReset(){return due()||MiningRules.depleted(remainingOre,initialOre,threshold);}
    void requestReset()throws IOException{
        if(!world.ready())throw new IllegalArgumentException("광산 리셋 중입니다.");reset.regenerate();
    }
    void onClose(Runnable action){onClose=action;}
    boolean due(){return clock.getAsLong()>=deadline;}
    long remaining(){return Math.max(0,deadline-clock.getAsLong());}
    void scheduleNext()throws IOException{
        long next=Math.addExact(clock.getAsLong(),INTERVAL_MILLIS);store.meta(DEADLINE,Long.toString(next));deadline=next;
    }
    static void show(MiningWorld world,Player player){current(world).add(player);}
    static void hide(MiningWorld world,Player player){var cycle=CYCLES.get(world);if(cycle!=null)cycle.remove(player);}
    static void update(MiningGame game,MiningWorld world,boolean failed){
        var cycle=CYCLES.get(world);if(cycle!=null)cycle.render(world.world.getPlayers().stream().filter(game::owns).toList(),world.ready(),failed);
    }
    static void close(MiningWorld world){var cycle=CYCLES.remove(world);if(cycle!=null)cycle.close();}
    void render(Collection<Player> players,boolean ready,boolean failed){
        long remaining=remaining(),seconds=(remaining+999)/1000;
        bar.name(MiningItems.text(failed?"광산 점검":!ready?"광산 리셋 중":String.format(Locale.ROOT,"광산 리셋 %02d:%02d",seconds/60,seconds%60)));
        bar.color(failed?BossBar.Color.RED:BossBar.Color.WHITE);
        bar.progress(failed||!ready?0:(float)Math.clamp(remaining/(double)INTERVAL_MILLIS,0,1));
        var wanted=new HashSet<UUID>();for(Player player:players){wanted.add(player.getUniqueId());add(player);}
        for(Player player:List.copyOf(viewers.values()))if(!wanted.contains(player.getUniqueId()))remove(player);
    }
    private void add(Player player){
        Player old=viewers.put(player.getUniqueId(),player);if(old==player)return;
        if(old!=null)old.hideBossBar(bar);player.showBossBar(bar);
    }
    private void remove(Player player){
        if(viewers.get(player.getUniqueId())==player){viewers.remove(player.getUniqueId());player.hideBossBar(bar);}
    }
    @Override public void close(){for(Player player:viewers.values())player.hideBossBar(bar);viewers.clear();onClose.run();}
}
