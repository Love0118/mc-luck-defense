package dev.moma.benchmark;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated Paper 20-TPS combat audit; never installed on a live server. */
public final class SpeedSmokePlugin extends JavaPlugin {
    private static final class Fixture {
        final Arena arena;
        final CombatEngine engine=new CombatEngine();
        final UnitType type;
        final int speed,bonus,interval;
        long tick,hits;
        Fixture(UnitType type,int speed,int bonus) {
            this.type=type;this.speed=speed;this.bonus=bonus;
            UUID owner=new UUID(0,1);
            var traits=new TraitLoadout(bonus==0?List.of():List.of(bonus==2?"mythic_10":"mythic_1000"));
            arena=new Arena("speed",owner,new Grid(6),30,100,traits,new HashRandom(1));
            arena.summon(owner,new SummonRoll(type,Rarity.PRIMORDIAL),(t,r,c)->new UUID(0,2));
            arena.select(owner,new UUID(0,2));arena.moveSelected(owner,new Cell(0,0));
            arena.addEnemy(new Enemy(new UUID(0,3),"speed",EnemyType.ZOMBIE,1e16,1e-9,0,false));
            interval=arena.lastSummoned().profile().intervalTicks();
        }
        void frame() {
            for(int i=0;i<speed;i++)engine.tick(arena,tick++,(d,e,damage)->hits++);
        }
    }
    private final List<Fixture> fixtures=new ArrayList<>();
    private int frames;
    private long started;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        Bukkit.getServerTickManager().setTickRate(20);
        for(UnitType type:UnitType.values())for(int speed:new int[]{1,2,4,8,16})for(int bonus:new int[]{0,2,8})fixtures.add(new Fixture(type,speed,bonus));
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try {
                if(frames++<200)return;
                if(started==0)started=System.nanoTime();
                for(Fixture fixture:fixtures)fixture.frame();
                if(frames==801)finish();
            } catch(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"SPEED_AUDIT_FAILED",error);Bukkit.shutdown();}
        },1,1);
    }
    private void finish()throws Exception {
        double elapsed=(System.nanoTime()-started)/1e9;
        List<String> rows=new ArrayList<>(List.of("unit,speed,bonus,game_ticks,attacks,expected_attacks"));
        for(Fixture f:fixtures) {
            long expected=(long)Math.floor((f.tick-1)/(f.interval/(1+f.bonus/100.0)))+1;
            if(Math.abs(expected-f.hits)>1)throw new IllegalStateException("Attack timing mismatch "+f.type+" "+f.speed+" "+f.bonus+": "+f.hits+"/"+expected);
            rows.add(f.type+","+f.speed+","+f.bonus+","+f.tick+","+f.hits+","+expected);
        }
        Files.write(Path.of("speed-audit.csv"),rows);
        Files.writeString(Path.of("speed-audit.json"),String.format(Locale.ROOT,
                "{\"targetTps\":20,\"measuredTps\":%.3f,\"seconds\":%.3f,\"frames\":601,\"fixtures\":%d,\"maxAttackCountError\":1,\"passed\":true}",600/elapsed,elapsed,fixtures.size()));
        getLogger().info("SPEED_AUDIT_PASSED");Bukkit.shutdown();
    }
}
