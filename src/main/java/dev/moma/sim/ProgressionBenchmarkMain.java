package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Each virtual 20 Hz frame runs 500 game ticks; wall-clock throughput is measured separately. */
public final class ProgressionBenchmarkMain {
    public static void main(String[] args)throws Exception {
        int runs=args.length>0?Integer.parseInt(args[0]):256;
        long seed=args.length>1?Long.parseLong(args[1]):28000000;
        Path output=Path.of(args.length>2?args[2]:"target/progression-019");Files.createDirectories(output);
        if(runs<1 || runs>100000)throw new IllegalArgumentException("Invalid run count");
        var passives=TraitCatalog.ALL.stream().filter(TraitCatalog.Entry::passive).map(TraitCatalog.Entry::id).toList();
        Map<String,TraitLoadout> cases=new LinkedHashMap<>();cases.put("none",TraitLoadout.EMPTY);
        var growth=new ArrayList<>(passives);growth.addAll(List.of("miracle_100","duplicate_10000","gold_spent_10000000"));
        cases.put("top_growth",new TraitLoadout(growth));
        var combat=new ArrayList<>(passives);combat.addAll(List.of("round_10000","mythic_1000","miracle_100"));
        cases.put("top_combat",new TraitLoadout(combat));
        if(args.length>3) {
            Set<String> selected=Set.of(args[3].split(","));
            if(!cases.keySet().containsAll(selected))throw new IllegalArgumentException("Unknown scenario");
            cases.keySet().retainAll(selected);
        }
        int[] checkpoints={30,60,100,200,300,500,700,1000,1500,2000,2500,3000,5000,7500,10000};
        Files.writeString(output.resolve("rules.txt"),"runs="+runs+"\nseed="+seed+"\nroundCap=10000\nbatchTicks=500\nrequestedVirtualSpeed=500\npolicy=reserve, grade-first placement, four transactions per game tick\n",StandardCharsets.UTF_8);
        var summary=new ArrayList<String>();String header="scenario,runs,wall_seconds,simulated_seconds,effective_speed,mean_round";
        for(int r:checkpoints)header+=",reach"+r;summary.add(header);
        try(var executor=Executors.newFixedThreadPool(Math.min(8,Runtime.getRuntime().availableProcessors()))) {
            for(var scenario:cases.entrySet()) {
                Files.writeString(output.resolve(scenario.getKey()+"-traits.txt"),String.join(",",scenario.getValue().allIds()));
                long start=System.nanoTime();var jobs=new ArrayList<Future<String>>();
                for(int i=0;i<runs;i++){long sample=seed+i;jobs.add(executor.submit(()->EndlessSimulatorMain.run(sample,10000,CampaignRules.standard(),scenario.getValue(),500)));}
                long ticks=0,totalRounds=0;int[] counts=new int[checkpoints.length];
                try(var out=new java.io.BufferedWriter(new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(output.resolve(scenario.getKey()+".jsonl.gz"))),StandardCharsets.UTF_8))) {
                    int done=0;
                    for(var job:jobs) {
                        String row=job.get();out.write(row);out.newLine();int round=number(row,"round");ticks+=number(row,"ticks");totalRounds+=round;
                        for(int j=0;j<counts.length;j++)if(round>=checkpoints[j])counts[j]++;
                        if(++done%64==0){out.flush();System.out.println(scenario.getKey()+" "+done+"/"+runs);}
                    }
                }
                double seconds=(System.nanoTime()-start)/1e9;
                String line=scenario.getKey()+","+runs+","+seconds+","+ticks/20.0+","+(ticks/20.0/seconds)+","+totalRounds/(double)runs;
                for(int count:counts)line+=","+count;
                summary.add(line);Files.write(output.resolve("summary.csv"),summary,StandardCharsets.UTF_8);System.out.println(line);
            }
        }
    }
    private static int number(String row,String name) {
        String prefix="\""+name+"\":";int start=row.indexOf(prefix)+prefix.length();return Integer.parseInt(row.substring(start,row.indexOf(',',start)));
    }
}
