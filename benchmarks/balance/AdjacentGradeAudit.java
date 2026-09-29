package dev.moma.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class AdjacentGradeAudit {
    private record Case(UnitType unit,Rarity low,int bonus,String scenario) {}
    private static final UUID OWNER=new UUID(0,1);
    private static final Grid GRID=new Grid(6);
    private static final Map<Double,Cell> CELLS=new ConcurrentHashMap<>();
    private static final int WARMUP=840,MEASURE=6720;
    public static void main(String[] args)throws Exception {
        var cases=new ArrayList<Case>();
        for(UnitType unit:UnitType.values())for(Rarity grade:Rarity.values())if(grade!=Rarity.MIRACLE)
            for(int bonus:new int[]{0,30})for(String scenario:List.of("normal","boss","crowd"))cases.add(new Case(unit,grade,bonus,scenario));
        List<String> rows;
        try(var pool=new ForkJoinPool(Math.min(12,Runtime.getRuntime().availableProcessors()))) {
            rows=pool.submit(()->cases.parallelStream().map(c->{
                double low=trial(c.unit,c.low,19,c.bonus,c.scenario);
                Rarity high=Rarity.values()[c.low.ordinal()+1];
                double higher=trial(c.unit,high,0,c.bonus,c.scenario);
                return String.format(Locale.ROOT,"%s,%s,%s,%d,%s,%.12g,%.12g,%.9f",c.unit,c.low,high,c.bonus,c.scenario,low,higher,higher/low);
            }).toList()).get();
        }
        Path output=Path.of(args[0]);Files.createDirectories(output.toAbsolutePath().getParent());
        var lines=new ArrayList<String>();lines.add("unit,lower19,higher0,bonus,scenario,lowerDps,higherDps,ratio");lines.addAll(rows);
        Files.write(output,lines);
        var profiles=new ArrayList<String>();
        profiles.add("unit,rarity,enhancement,bonus,damage,interval,range,rawDps");
        for(UnitType unit:UnitType.values())for(Rarity grade:Rarity.values())for(int bonus:new int[]{0,30}) {
            var d=new Defender(new UUID(0,2),OWNER,"adjacent",unit,grade,new Cell(0,0),bonus/100.0,0);
            for(int n=0;n<20;n++) {
                var p=d.profile();
                profiles.add(String.format(Locale.ROOT,"%s,%s,%d,%d,%.17g,%d,%.17g,%.17g",unit,grade,n,bonus,
                        p.damage(),p.intervalTicks(),p.range(),p.damage()*20/p.intervalTicks()));
                if(n<19)d.merge();
            }
        }
        Files.write(output.resolveSibling("profiles.csv"),profiles);
        long failures=rows.stream().filter(r->Double.parseDouble(r.substring(r.lastIndexOf(',')+1))<=1).count();
        System.out.println("Compared "+rows.size()+" adjacent-grade moving-combat cases; reversals="+failures);
        if(failures>0)throw new IllegalStateException("Inspect cases where higher0/low19 <= 1");
    }
    private static double trial(UnitType type,Rarity rarity,int enhancement,int bonus,String scenario) {
        var traits=bonus==0?TraitLoadout.EMPTY:new TraitLoadout(List.of("miracle_100"));
        var arena=new Arena("adjacent",OWNER,GRID,1000,100,traits,new HashRandom(1));
        for(int n=0;n<=enhancement;n++)arena.summon(OWNER,new SummonRoll(type,rarity),(t,r,c)->new UUID(0,2));
        Defender d=arena.lastSummoned();d.move(CELLS.computeIfAbsent(d.profile().range(),AdjacentGradeAudit::bestCell));
        int count=scenario.equals("crowd")?48:1;
        for(int i=0;i<count;i++) {
            var enemy=new Enemy(new UUID(0,i+100),arena.id(),EnemyType.ZOMBIE,d.profile().damage()*1e10,2,0,scenario.equals("boss"));
            for(int k=0;k<(int)Math.round(i*840.0/count);k++)enemy.advance(k);
            arena.addEnemy(enemy);
        }
        var engine=new CombatEngine();double[] damage={0};
        CombatEngine.HitSink sink=(defender,enemy,amount)->damage[0]+=amount;
        for(int tick=0;tick<WARMUP+MEASURE;tick++)engine.tick(arena,tick,tick<WARMUP?null:sink);
        if(arena.activeEnemies().stream().anyMatch(e->!e.alive()))throw new IllegalStateException("Target died");
        return damage[0]/(MEASURE/20.0);
    }
    private static Cell bestCell(double range) {
        Cell best=null;int maximum=-1;
        for(Cell cell:GRID.placementOrder()) {
            int count=0;for(int i=0;i<840;i++)if(cell.point().distanceSquared(GRID.route().at(i*.1))<=range*range)count++;
            if(count>maximum){best=cell;maximum=count;}
        }
        return best;
    }
}
