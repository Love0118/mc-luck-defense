package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;

public final class LongRunBatchProbe {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        List<String> results=new ArrayList<>();
        for(int i=1;i<args.length;i++) {
            boolean growth=args[i].startsWith("growth:");
            long seed=Long.parseLong(growth?args[i].substring(7):args[i]);String baseline=null;
            var ids=new ArrayList<>(TraitCatalog.ALL.stream().filter(TraitCatalog.Entry::passive).map(TraitCatalog.Entry::id).toList());
            ids.addAll(growth?List.of("miracle_100","duplicate_10000","gold_spent_10000000"):List.of("round_10000","mythic_1000","miracle_100"));
            TraitLoadout traits=new TraitLoadout(ids);
            for(int batch:new int[]{1,32,500}) {
                long start=System.nanoTime();String result=EndlessSimulatorMain.run(seed,10000,CampaignRules.standard(),traits,batch);
                if(baseline==null)baseline=result;else if(!baseline.equals(result))throw new AssertionError("Different outcome at batch "+batch);
                Files.writeString(output.resolve(seed+"-"+batch+".json"),result+"\n");
                String row=seed+","+batch+","+(System.nanoTime()-start)/1e9+",identical";results.add(row);System.out.println(row);
            }
        }
        Files.write(output.resolve("equivalence.csv"),results);
    }
}
