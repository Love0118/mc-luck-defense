package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Biased seed selection for rare-branch diagnostics, never a population reach-rate estimate. */
public final class RareDrawProbeMain {
    record Candidate(long seed,int draw,UnitType type) {}
    private static Candidate scan(long seed,int minimum,int maximum) {
        var random=new HashRandom(seed);UnitType[] types=UnitType.values();
        for(int draw=1;draw<=maximum;draw++) {
            int roll=random.nextInt(Rarity.TOTAL_WEIGHT);UnitType type=types[random.nextInt(types.length)];
            if(draw>=minimum && roll==Rarity.TOTAL_WEIGHT-1)return new Candidate(seed,draw,type);
        }
        return null;
    }
    public static void main(String[] args)throws Exception {
        long first=Long.parseLong(args[0]);int seeds=Integer.parseInt(args[1]);
        int minimum=Integer.parseInt(args[2]),maximum=Integer.parseInt(args[3]);
        double power=Double.parseDouble(args[4]),bend=Double.parseDouble(args[5]);Path output=Path.of(args[6]);
        String loadout=args.length>7?args[7]:"top_fusion";
        if(seeds<1 || seeds>100000 || minimum<1 || maximum<minimum || maximum>100000)
            throw new IllegalArgumentException("Invalid scan bounds");
        Math.addExact(first,seeds-1L);
        CampaignRules rules=CampaignRules.standard().withEndlessHealthPower(power).withEndlessPressureBend(bend);
        TraitLoadout traits=Objects.requireNonNull(ProgressionBenchmarkMain.loadouts().get(loadout));
        Files.createDirectories(output);
        Files.writeString(output.resolve("DIAGNOSTIC.txt"),"BIASED SEED SELECTION; NOT A POPULATION REACH RATE.\n"
                +"Only candidate seeds are replayed; the player policy has no knowledge of future draws.\n"
                +"seedStart="+first+"\nscannedSeeds="+seeds+"\nminimumDraw="+minimum+"\nmaximumDraw="+maximum
                +"\nloadout="+loadout+"\nrules="+rules+"\n");
        try(var pool=Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            var scans=new ArrayList<Future<Candidate>>();
            for(int i=0;i<seeds;i++){long seed=first+i;scans.add(pool.submit(()->scan(seed,minimum,maximum)));}
            var candidates=new ArrayList<Candidate>();
            for(var future:scans){Candidate candidate=future.get();if(candidate!=null)candidates.add(candidate);}
            var csv=new ArrayList<String>();csv.add("seed,draw_index,raw_type");
            for(Candidate candidate:candidates)csv.add(candidate.seed()+","+candidate.draw()+","+candidate.type());
            Files.write(output.resolve("selected-seeds.csv"),csv);
            System.out.println("Selected "+candidates.size()+" candidates from "+seeds+" RNG streams; replaying unchanged game rules");
            var completion=new ExecutorCompletionService<String>(pool);
            for(Candidate candidate:candidates)completion.submit(()->EndlessSimulatorMain.run(candidate.seed(),2500,rules,traits));
            int[] reached=new int[5];int[] gates={1000,1500,2000,2250,2500};
            try(var journal=Files.newBufferedWriter(output.resolve("diagnostic-runs.jsonl"))) {
                for(int done=1;done<=candidates.size();done++) {
                    String row=completion.take().get();journal.write(row);journal.newLine();
                    int start=row.indexOf("\"round\":")+8;int round=Integer.parseInt(row.substring(start,row.indexOf(',',start)));
                    for(int i=0;i<gates.length;i++)if(round>=gates[i])reached[i]++;
                    if(done%50==0 || done==candidates.size()){journal.flush();System.out.println("Diagnostic replays "+done+"/"+candidates.size()+" reached "+Arrays.toString(reached));}
                }
            }
        }
    }
}
