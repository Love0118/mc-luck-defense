package dev.moma.benchmark;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;

/** Controlled promotion experiment: counts Primordial arrivals, not ordinary purchases or round clears. */
public final class DuplicatePromotionProbe {
    public static void main(String[] args)throws Exception {
        List<String> rows=new ArrayList<>(List.of("trait,runs,mean_primordial_arrivals,p50,p95"));
        for(String id:List.of("none","duplicate_100","duplicate_500","duplicate_2000","duplicate_10000")) {
            int[] draws=new int[2000];
            for(int i=0;i<draws.length;i++) {
                long seed=27000000L+i;HashRandom random=new HashRandom(seed);long[] sequence={0};
                Arena arena=new Arena("promotion",new UUID(0,1),new Grid(6),100000,100,
                        new TraitLoadout(id.equals("none")?List.of():List.of(id)),new HashRandom(seed ^ 0x545241495453L));
                do {
                    UnitType original=UnitType.values()[random.nextInt(UnitType.values().length)];
                    UnitType type=arena.summonType(original,Rarity.PRIMORDIAL);
                    if(arena.summon(arena.owner(),new SummonRoll(type,Rarity.PRIMORDIAL),(t,r,c)->new UUID(0,++sequence[0]))!=Arena.Result.OK)
                        throw new IllegalStateException("Invalid promotion fixture");
                    draws[i]++;
                } while(arena.lastSummoned().rarity()!=Rarity.TRUE_PRIMORDIAL);
            }
            Arrays.sort(draws);
            rows.add(id+","+draws.length+","+Arrays.stream(draws).average().orElseThrow()+","+draws[draws.length/2]+","+draws[(int)(draws.length*.95)]);
        }
        Files.write(Path.of(args[0]),rows);rows.forEach(System.out::println);
    }
}
