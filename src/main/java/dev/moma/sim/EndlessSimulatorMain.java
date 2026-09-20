package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real combat to a round cap; reaching the cap is not a forced victory. */
public final class EndlessSimulatorMain {
    public static void main(String[] args) throws Exception {
        int runs=Integer.parseInt(args[0]),cap=Integer.parseInt(args[1]);long first=Long.parseLong(args[2]);
        if(runs<1 || runs>100000 || cap<1 || cap>100000)throw new IllegalArgumentException("Runs/cap must be 1..100000");
        Path output=Path.of(args[3]);Files.createDirectories(output);
        CampaignRules rules=CampaignRules.standard();
        if(args.length>4)rules=rules.withHealthScale(Double.parseDouble(args[4]));
        final CampaignRules config=rules;
        List<String> rows=Collections.synchronizedList(new ArrayList<>());
        long start=System.nanoTime();
        var done=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(Math.min(6,Runtime.getRuntime().availableProcessors()))) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<runs;i++) {final long seed=first+i;jobs.add(executor.submit(()->{
                rows.add(run(seed,cap,config));int count=done.incrementAndGet();if(count%100==0)System.out.printf("Completed %d/%d%n",count,runs);
            }));}
            for(var job:jobs)job.get();
        }
        rows.sort(Comparator.naturalOrder());Files.write(output.resolve("runs.jsonl"),rows);
        System.out.printf(Locale.ROOT,"Completed %d runs to cap %d in %.2fs%n",runs,cap,(System.nanoTime()-start)/1e9);
    }
    static String run(long seed,int cap,CampaignRules rules) {
        UUID owner=new UUID(0,1);long[] seq={2};var ids=(java.util.function.Supplier<UUID>)()->new UUID(seed,seq[0]++);
        Arena arena=new Arena("endless",owner,new Grid(rules.gridSize()),rules.startingCoins(),rules.enemyLimit());
        Campaign campaign=new Campaign(rules,true);CombatEngine combat=new CombatEngine();
        AutoPlayer bot=new AutoPlayer(seed,AutoPlayer.Strategy.BALANCED,arena.grid(),ids);
        int[] checkpoints={20,26,30,40,50,60,90,100,101,150,200,300,500,750,1000,1500,2000};
        var snapshots=new ArrayList<String>();int last=0,maxGrade=0;
        long tick=0,limit=rules.preparationTicks()+(long)rules.roundTicks()*cap;
        while(tick<limit && !arena.ended()) {
            campaign.beforeCombat(arena,s->ids.get());bot.act(arena,tick);combat.tick(arena,tick);arena.collectDeadEnemies();campaign.afterCombat(arena);
            for(Defender d:arena.activeDefenders())maxGrade=Math.max(maxGrade,d.rarity().ordinal());
            if(campaign.round()!=last) {
                last=campaign.round();
                if(Arrays.binarySearch(checkpoints,last)>=0)snapshots.add(String.format(Locale.ROOT,
                        "{\"round\":%d,\"summons\":%d,\"sales\":%d,\"earned\":%.1f,\"coins\":%.1f,\"truePrimordial\":%d}",last,bot.summons(),bot.sales(),arena.earnedCoins(),arena.coins(),arena.activeDefenders().stream().filter(d->d.rarity()==Rarity.TRUE_PRIMORDIAL).count()));
            }
            tick++;
        }
        return String.format(Locale.ROOT,"{\"seed\":%d,\"round\":%d,\"completedRounds\":%d,\"outcome\":\"%s\",\"ticks\":%d,\"summons\":%d,\"sales\":%d,\"maxGrade\":\"%s\",\"checkpoints\":[%s]}",
                seed,campaign.round(),campaign.completedRounds(),arena.outcome(),tick,bot.summons(),bot.sales(),Rarity.values()[maxGrade],String.join(",",snapshots));
    }
}
