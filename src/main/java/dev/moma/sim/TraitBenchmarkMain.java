package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Paired seeds for every individual trait/tier and representative multi-slot combinations. */
public final class TraitBenchmarkMain {
    public static void main(String[] args)throws Exception {
        int runs=args.length>0?Integer.parseInt(args[0]):256,cap=args.length>1?Integer.parseInt(args[1]):300;
        long seed=args.length>2?Long.parseLong(args[2]):23000000;
        Path output=Path.of(args.length>3?args[3]:"target/trait-benchmark");Files.createDirectories(output);
        if(runs<1 || runs>10000 || cap<1 || cap>10000)throw new IllegalArgumentException("Invalid benchmark size");
        Map<String,TraitLoadout> scenarios=new LinkedHashMap<>();scenarios.put("none",TraitLoadout.EMPTY);
        for(var trait:TraitCatalog.ALL)scenarios.put(trait.id(),new TraitLoadout(List.of(trait.id())));
        scenarios.put("start_two",new TraitLoadout(List.of("round_150","round_250")));
        scenarios.put("start_three",new TraitLoadout(List.of("round_750","round_250","round_2000")));
        scenarios.put("opening_three",new TraitLoadout(List.of("round_750","round_350","session_1000")));
        scenarios.put("epic_opening_three",new TraitLoadout(List.of("round_750","round_350","session_500")));
        scenarios.put("combat_three",new TraitLoadout(List.of("round_2000","mythic_1000","true_primordial_100")));
        scenarios.put("forge_three",new TraitLoadout(List.of("enhancement_50000","mythic_1000","true_primordial_100")));
        scenarios.put("boss_three",new TraitLoadout(List.of("round_2000","primordial_100","mythic_1000")));
        var passives=TraitCatalog.ALL.stream().filter(TraitCatalog.Entry::passive).map(TraitCatalog.Entry::id).toList();
        scenarios.put("all_passives",new TraitLoadout(passives));
        var duplicate=new ArrayList<>(passives);duplicate.add("duplicate_10000");
        scenarios.put("passives_duplicate",new TraitLoadout(duplicate));
        var investor=new ArrayList<>(passives);investor.add("gold_spent_10000000");
        scenarios.put("passives_income",new TraitLoadout(investor));
        if(args.length>4) {
            Set<String> selected=new LinkedHashSet<>(List.of(args[4].split(",")));
            if(!scenarios.keySet().containsAll(selected))throw new IllegalArgumentException("Unknown scenario");
            scenarios.keySet().retainAll(selected);
        }
        int[] checkpoints={30,60,100,150,200,250,300,500,700};
        var config=CampaignRules.standard();
        Files.writeString(output.resolve("rules.txt"),"runs="+runs+"\ncap="+cap+"\nseed="+seed+"\ncurve="+config.healthCurve().specification()+"\n");
        List<String> catalog=new ArrayList<>(List.of("id\tfamily\tvalue\tdescription"));
        for(var entry:TraitCatalog.ALL)catalog.add(entry.id()+"\t"+entry.family()+"\t"+entry.value()+"\t"+entry.description());
        Files.write(output.resolve("catalog.tsv"),catalog,StandardCharsets.UTF_8);
        List<String> summary=new ArrayList<>();
        summary.add("scenario,traits,runs,mean_round,reach30,reach60,reach100,reach150,reach200,reach250,reach300,reach500,reach700");
        try(var executor=Executors.newFixedThreadPool(Math.min(8,Runtime.getRuntime().availableProcessors()))) {
            for(var scenario:scenarios.entrySet()) {
                List<Future<String>> jobs=new ArrayList<>();
                for(int i=0;i<runs;i++){long sample=seed+i;jobs.add(executor.submit(()->EndlessSimulatorMain.run(sample,cap,config,scenario.getValue())));}
                int[] counts=new int[checkpoints.length];long total=0;
                try(var out=new java.io.BufferedWriter(new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(output.resolve(scenario.getKey()+".jsonl.gz"))),StandardCharsets.UTF_8))) {
                    for(var job:jobs) {
                        String row=job.get();out.write(row);out.newLine();
                        int round=Integer.parseInt(row.substring(row.indexOf(",\"round\":")+9,row.indexOf(",\"completedRounds\":")));
                        total+=round;for(int j=0;j<counts.length;j++)if(round>=checkpoints[j])counts[j]++;
                    }
                }
                String line=scenario.getKey()+","+String.join("+",scenario.getValue().allIds())+","+runs+","+total/(double)runs;
                for(int value:counts)line+=","+value;
                summary.add(line);Files.write(output.resolve("summary.csv"),summary,StandardCharsets.UTF_8);
                System.out.println(line);
            }
        }
    }
    private TraitBenchmarkMain() {}
}
