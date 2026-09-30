package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DamageShareProbe {
    private static final int WIDTH=100, GRADES=Rarity.values().length, BUCKETS=5;
    private static int startRound=700;
    private static int bucket(int n) { return n==0?0:n<5?1:n<10?2:n<20?3:4; }
    private static double[] targetMultipliers;
    private static final class Meter implements CombatEngine.HitSink {
        final Arena arena;
        final double[][] damage,loss,raw,rawLoss;
        final long[][] hits;
        final double enhancementBonus;
        Defender previous;
        int window;
        Meter(Arena arena,int cap) {
            this.arena=arena;enhancementBonus=arena.traits().value(TraitCatalog.Family.ENHANCEMENT)/100.0;
            if(arena.traits().value(TraitCatalog.Family.CRITICAL)!=0)throw new IllegalArgumentException("Critical reconstruction not supported");
            int windows=(cap-startRound)/WIDTH+1,columns=GRADES*BUCKETS;
            damage=new double[windows][columns];loss=new double[windows][columns];raw=new double[windows][columns];
            rawLoss=new double[windows][columns];hits=new long[windows][columns];
        }
        @Override public void hit(Defender d,Enemy enemy,double effective) {
            boolean primary=previous!=d;previous=d;
            int level=d.rarity().abilityLevel(),n=d.enhancement(),column=d.rarity().ordinal()*BUCKETS+bucket(n);
            double amount=d.profile().damage();
            if(level>0)amount*=switch(d.type().role()) {
                case MELEE_SINGLE -> 1+.06*level*(d.consecutiveHits()-1);
                case RANGED_SINGLE -> enemy.boss()?1+.5*level:1;
                case SMALL_AREA -> primary?1+.3*level:1;
                default -> 1;
            };
            amount*=arena.damageMultiplier(d.type().role(),enemy.boss());
            double originalHealth=enemy.health()+effective;
            if(Math.abs(Math.min(originalHealth,amount)-effective)>Math.max(1e-9,effective*1e-9))
                throw new IllegalStateException("Hit reconstruction differs from CombatEngine");
            double canonical=d.type().profile().damage()*targetMultipliers[d.rarity().ordinal()]
                    *(1+n*(1+enhancementBonus)+(n/5)*.5);
            double ratio=canonical/d.profile().damage();
            damage[window][column]+=effective;raw[window][column]+=amount;hits[window][column]++;
            loss[window][column]+=effective-Math.min(originalHealth,amount*ratio);
            rawLoss[window][column]+=amount*(1-ratio);
        }
    }
    private static String run(long seed,int cap,String scenario) {
        var rules=CampaignRules.standard();var traits=ProgressionBenchmarkMain.loadouts().get(scenario);
        UUID owner=new UUID(0,1);long[] seq={2};var ids=(java.util.function.Supplier<UUID>)()->new UUID(seed,seq[0]++);
        var arena=new Arena("endless",owner,new Grid(rules.gridSize()),rules.startingCoins(),rules.enemyLimit(),traits,new HashRandom(seed^0x545241495453L));
        var campaign=new Campaign(rules,true);var combat=new CombatEngine();var bot=new AutoPlayer(seed,AutoPlayer.Strategy.BALANCED,arena.grid(),ids);
        var meter=new Meter(arena,cap);long tick=0,limit=rules.preparationTicks()+(long)rules.roundTicks()*cap;
        while(tick<limit && !arena.ended()) {
            campaign.beforeCombat(arena,s->ids.get());bot.act(arena,tick);
            meter.previous=null;meter.window=(campaign.round()-startRound)/WIDTH;
            combat.tick(arena,tick,campaign.round()>=startRound?meter:null);
            arena.collectDeadEnemies();campaign.afterCombat(arena);tick++;
        }
        var text=new StringBuilder();
        for(int w=0;w<meter.damage.length;w++)for(int c=0;c<GRADES*BUCKETS;c++)if(meter.hits[w][c]>0)
            text.append(String.format(Locale.ROOT,"%d,%d,%d,%s,%d,%d,%.17g,%.17g,%.17g,%.17g,%d,%d,%d,%d,%s%n",
                    seed,startRound+w*WIDTH,Math.min(cap,startRound+(w+1)*WIDTH-1),Rarity.values()[c/BUCKETS],c%BUCKETS,meter.hits[w][c],
                    meter.damage[w][c],meter.loss[w][c],meter.raw[w][c],meter.rawLoss[w][c],campaign.round(),tick,bot.summons(),bot.sales(),arena.outcome()));
        return text.toString();
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("--multipliers")) {
            System.out.println(String.join(",",Arrays.stream(Rarity.values()).map(r->Double.toString(r.damageMultiplier())).toList()));return;
        }
        var seeds=Files.readAllLines(Path.of(args[0])).stream().filter(s->!s.isBlank()).map(Long::parseLong).toList();
        targetMultipliers=Arrays.stream(Files.readString(Path.of(args[1])).strip().split(",")).mapToDouble(Double::parseDouble).toArray();
        if(targetMultipliers.length!=GRADES)throw new IllegalArgumentException("Grade count differs");
        Path output=Path.of(args[2]);Files.createDirectories(output.toAbsolutePath().getParent());
        int threads=Integer.parseInt(args[3]),cap=Integer.parseInt(args[4]);String scenario=args[5];
        if(args.length>6)startRound=Integer.parseInt(args[6]);
        var done=new AtomicInteger();long start=System.nanoTime();
        try(var writer=Files.newBufferedWriter(output);var pool=Executors.newFixedThreadPool(threads)) {
            writer.write("seed,start,end,grade,enhancementBucket,hits,damage,lostDamage,rawDamage,lostRawDamage,lastRound,ticks,summons,sales,outcome\n");
            var tasks=new ArrayList<Future<?>>();
            for(long seed:seeds)tasks.add(pool.submit(()-> {
                String result=run(seed,cap,scenario);
                synchronized(writer){try{writer.write(result);writer.flush();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
                int n=done.incrementAndGet();if(n%25==0 || n==seeds.size())System.out.printf(Locale.ROOT,"%d/%d %.1fs%n",n,seeds.size(),(System.nanoTime()-start)/1e9);
            }));
            for(var task:tasks)task.get();
        }
    }
}
