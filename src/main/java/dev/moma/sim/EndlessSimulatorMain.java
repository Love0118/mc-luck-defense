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
        if(args.length>5)rules=rules.withHealthCurve(HealthCurve.parse(args[5]));
        final CampaignRules config=rules;
        Set<Long> seeds=args.length>6 && !args[6].equals("-")?new HashSet<>(Files.readAllLines(Path.of(args[6])).stream().map(Long::parseLong).toList()):null;
        TraitLoadout traits=args.length>7 && !args[7].equals("-")?new TraitLoadout(List.of(args[7].split(","))):TraitLoadout.EMPTY;
        if(seeds!=null && seeds.stream().anyMatch(seed->seed<first || seed>=first+runs))throw new IllegalArgumentException("Filtered seed outside cohort");
        int scheduled=seeds==null?runs:seeds.size();
        List<String> rows=Collections.synchronizedList(new ArrayList<>());
        int[] reportedRounds={30,60,100,200,300,400,500,600,700,1000,2000,2500,3000,5000,7500,10000};
        var checkpointCounts=new java.util.concurrent.atomic.AtomicIntegerArray(reportedRounds.length);
        Path partial=output.resolve("partial.jsonl");
        java.io.BufferedWriter progress=Files.newBufferedWriter(partial);
        long start=System.nanoTime();
        var done=new java.util.concurrent.atomic.AtomicInteger();
        Files.writeString(output.resolve("rules.json"),String.format(Locale.ROOT,
                "{\"runs\":%d,\"cap\":%d,\"seedStart\":%d,\"healthScale\":%.8f,\"healthCurve\":\"%s\",\"bossScale\":%.8f,\"policy\":\"BALANCED\"}",runs,cap,first,rules.healthScale(),rules.healthCurve().specification(),rules.bossHealthScale()));
        Files.writeString(output.resolve("economy.json"),String.format(Locale.ROOT,
                "{\"normalCost\":%d,\"advancedCost\":%d,\"normalWeights\":%s,\"advancedWeights\":%s,\"salePrices\":%s}",
                SummonTier.NORMAL.cost(),SummonTier.ADVANCED.cost(),
                Arrays.toString(Arrays.stream(Rarity.values()).mapToInt(r->SummonTier.NORMAL.weight(r,false)).toArray()),
                Arrays.toString(Arrays.stream(Rarity.values()).mapToInt(r->SummonTier.ADVANCED.weight(r,false)).toArray()),
                Arrays.toString(Arrays.stream(Rarity.values()).mapToInt(r->r.salePrice().orElse(-1)).toArray())));
        Files.writeString(output.resolve("traits.txt"),String.join(",",traits.ids()));
        try(progress; var executor=Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<runs;i++) {final long seed=first+i;if(seeds!=null&&!seeds.contains(seed))continue;jobs.add(executor.submit(()->{
                String row=run(seed,cap,config,traits);rows.add(row);
                synchronized(progress) { try {progress.write(row);progress.newLine();} catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);} }
                int reached=Integer.parseInt(row.substring(row.indexOf(",\"round\":")+9,row.indexOf(",\"completedRounds\":")));
                for(int c=0;c<reportedRounds.length;c++)if(reached>=reportedRounds[c])checkpointCounts.incrementAndGet(c);
                int count=done.incrementAndGet();if(count%1000==0) {
                    synchronized(progress) {try {progress.flush();} catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);} }
                    System.out.printf("Completed %d/%d; checkpoint counts %s%n",count,scheduled,checkpointCounts);
                }
            }));}
            for(var job:jobs)job.get();
        }
        rows.sort(Comparator.naturalOrder());Files.write(output.resolve("runs.jsonl"),rows);
        System.out.printf(Locale.ROOT,"Completed %d runs to cap %d in %.2fs%n",rows.size(),cap,(System.nanoTime()-start)/1e9);
    }
    static String run(long seed,int cap,CampaignRules rules) {
        return run(seed,cap,rules,TraitLoadout.EMPTY);
    }
    static String run(long seed,int cap,CampaignRules rules,TraitLoadout traits) {
        return run(seed,cap,rules,traits,500);
    }
    static String run(long seed,int cap,CampaignRules rules,TraitLoadout traits,int batchTicks) {
        if(batchTicks<1 || batchTicks>500)throw new IllegalArgumentException("Batch ticks must be 1..500");
        UUID owner=new UUID(0,1);long[] seq={2};var ids=(java.util.function.Supplier<UUID>)()->new UUID(seed,seq[0]++);
        Arena arena=new Arena("endless",owner,new Grid(rules.gridSize()),rules.startingCoins(),rules.enemyLimit(),
                traits,new HashRandom(seed ^ 0x545241495453L));
        Campaign campaign=new Campaign(rules,true);CombatEngine combat=new CombatEngine();
        AutoPlayer bot=new AutoPlayer(seed,AutoPlayer.Strategy.BALANCED,arena.grid(),ids);
        int[] checkpoints={20,26,30,40,50,60,90,100,101,150,200,250,300,350,400,450,500,550,600,650,700,750,800,900,1000,1250,1500,1750,2000,2100,2250,2400,2500,3000,4000,5000,6000,7500,9000,10000};
        var snapshots=new ArrayList<String>();int last=0,maxGrade=0,previousSummons=0,firstMiracleRound=0;
        String firstMiracleType="";
        long tick=0,limit=rules.preparationTicks()+(long)rules.roundTicks()*cap;
        while(tick<limit && !arena.ended()) {
          if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Simulation interrupted");
          for(int batch=0;batch<batchTicks && tick<limit && !arena.ended();batch++) {
            campaign.beforeCombat(arena,s->ids.get());bot.act(arena,tick);combat.tick(arena,tick,null);arena.collectDeadEnemies();campaign.afterCombat(arena);
            if(bot.summons()!=previousSummons) {
                for(Defender d:arena.units()) {
                    maxGrade=Math.max(maxGrade,d.rarity().ordinal());
                    if(d.rarity()==Rarity.MIRACLE && firstMiracleType.isEmpty()) {
                        firstMiracleRound=campaign.round();firstMiracleType=d.type().name();
                    }
                }
                previousSummons=bot.summons();
            }
            if(campaign.round()!=last) {
                last=campaign.round();
                if(Arrays.binarySearch(checkpoints,last)>=0)snapshots.add(String.format(Locale.ROOT,
                        "{\"round\":%d,\"summons\":%d,\"sales\":%d,\"earned\":%.1f,\"coins\":%.1f,\"truePrimordial\":%d}",last,bot.summons(),bot.sales(),arena.earnedCoins(),arena.coins(),arena.activeDefenders().stream().filter(d->d.rarity()==Rarity.TRUE_PRIMORDIAL).count()));
            }
            tick++;
          }
        }
        return String.format(Locale.ROOT,"{\"seed\":%d,\"round\":%d,\"completedRounds\":%d,\"outcome\":\"%s\",\"ticks\":%d,\"summons\":%d,\"sales\":%d,\"maxGrade\":\"%s\",\"firstMiracleRound\":%d,\"firstMiracleType\":\"%s\",\"checkpoints\":[%s]}",
                seed,campaign.round(),campaign.completedRounds(),arena.outcome(),tick,bot.summons(),bot.sales(),Rarity.values()[maxGrade],firstMiracleRound,firstMiracleType,String.join(",",snapshots));
    }
}
