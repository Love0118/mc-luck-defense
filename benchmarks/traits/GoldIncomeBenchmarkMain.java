package dev.moma.sim;

import dev.moma.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Offline sweep using the production reward/combat/bot paths; no runtime tuning flags. */
public final class GoldIncomeBenchmarkMain {
    public static void main(String[] args)throws Exception {
        int runs=Integer.parseInt(args[0]),cap=Integer.parseInt(args[1]);
        long seed=Long.parseLong(args[2]);Path output=Path.of(args[3]);
        if(runs<1 || runs>10000 || cap<1 || cap>2000)throw new IllegalArgumentException();
        Files.createDirectories(output);
        String[] contexts=args.length>5?args[5].split(","):new String[]{"isolated","passives"};
        Set<String> selected=args.length>4 && !args[4].equals("all")?new LinkedHashSet<>(List.of(args[4].split(","))):null;
        var rules=CampaignRules.standard();
        int[] checkpoints={30,60,100,200,300,500,700,1000,2000};
        List<String> summary=new ArrayList<>(List.of("context,scenario,runs,mean_round,mean_summons,reach30,reach60,reach100,reach200,reach300,reach500,reach700,reach1000,reach2000"));
        Files.writeString(output.resolve("rules.txt"),"runs="+runs+"\ncap="+cap+"\nseed="+seed+"\ncurve="+rules.healthCurve().specification()+"\ncontexts="+String.join(",",contexts)+"\npolicy=BALANCED\nincome=kill rewards only; fractional bonus carried in Arena\n",StandardCharsets.UTF_8);
        try(var executor=Executors.newFixedThreadPool(Math.min(8,Runtime.getRuntime().availableProcessors()))) {
            for(String context:contexts) {
                List<String> base=new ArrayList<>();
                if(!context.equals("isolated"))base.addAll(TraitCatalog.ALL.stream().filter(TraitCatalog.Entry::passive).map(TraitCatalog.Entry::id).toList());
                if(context.equals("endgame")){base.add("enhancement_50000");base.add("duplicate_10000");}
                else if(!Set.of("isolated","passives").contains(context))throw new IllegalArgumentException(context);
                Map<String,TraitLoadout> scenarios=new LinkedHashMap<>();
                scenarios.put("none",new TraitLoadout(base));
                for(int bonus:new int[]{1,2,3,4,5,6,8,10,12,15,20}) {
                    var ids=new ArrayList<>(base);ids.add("gold_spent_10000000");
                    TraitLoadout traits=new TraitLoadout(ids);
                    // Candidate values exist only in this offline harness, never in server configuration.
                    var field=TraitLoadout.class.getDeclaredField("values");field.setAccessible(true);
                    ((int[])field.get(traits))[TraitCatalog.Family.GOLD_INCOME.ordinal()]=bonus;
                    scenarios.put("gold_"+bonus,traits);
                }
                for(String id:List.of("round_200","round_700","round_2000","mythic_10","mythic_100","mythic_1000")) {
                    var ids=new ArrayList<>(base);ids.add(id);TraitCatalog.Entry entry=TraitCatalog.find(id);
                    scenarios.put(entry.family().name().toLowerCase(Locale.ROOT)+"_"+entry.value(),new TraitLoadout(ids));
                }
                if(selected!=null){if(!scenarios.keySet().containsAll(selected))throw new IllegalArgumentException("Unknown scenario");scenarios.keySet().retainAll(selected);}
                for(var scenario:scenarios.entrySet()) {
                    var jobs=new ArrayList<Future<String>>();
                    for(int i=0;i<runs;i++){long sample=seed+i;jobs.add(executor.submit(()->EndlessSimulatorMain.run(sample,cap,rules,scenario.getValue())));}
                    int[] counts=new int[checkpoints.length];long total=0,summons=0;
                    try(var out=new java.io.BufferedWriter(new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(output.resolve(context+"-"+scenario.getKey()+".jsonl.gz"))),StandardCharsets.UTF_8))) {
                        for(var job:jobs) {
                            String row=job.get();out.write(row);out.newLine();
                            int round=number(row,"round"),purchases=number(row,"summons");total+=round;summons+=purchases;
                            for(int j=0;j<counts.length;j++)if(round>=checkpoints[j])counts[j]++;
                        }
                    }
                    String line=context+","+scenario.getKey()+","+runs+","+total/(double)runs+","+summons/(double)runs;
                    for(int count:counts)line+=","+count;
                    summary.add(line);Files.write(output.resolve("summary.csv"),summary,StandardCharsets.UTF_8);
                    System.out.println(line);
                }
            }
        }
    }
    private static int number(String row,String key) {
        String prefix="\""+key+"\":";int start=row.indexOf(prefix)+prefix.length();
        return Integer.parseInt(row.substring(start,row.indexOf(',',start)));
    }
}
