package dev.moma.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class EnhancementAudit {
    private static final UUID OWNER=new UUID(0,1);
    private static final Grid GRID=new Grid(6);
    private static final Map<Double,Cell> CELLS=new ConcurrentHashMap<>();
    private static final int WARMUP=840, MEASURE=6720;
    private record Trial(UnitType unit,Rarity rarity,int enhancement,String scenario) {}

    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var bonuses=new TreeSet<Integer>();bonuses.add(0);
        TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.ENHANCEMENT).forEach(e->bonuses.add(e.value()));
        int maxBonus=bonuses.last();
        var stats=new ArrayList<Map<String,Object>>();
        var promotions=new ArrayList<Map<String,Object>>();
        var tiers=new ArrayList<Map<String,Object>>();
        var factors=new ArrayList<Map<String,Object>>();
        for(int bonus:bonuses)for(int n=0;n<=20;n++)
            factors.add(row("bonus",bonus,"enhancement",n,"multiplier",1+n*(1+bonus/100.0)+(n/5)*.5));
        for(Rarity rarity:Rarity.values()) {
            var weights=new LinkedHashMap<String,Object>();
            for(SummonTier tier:SummonTier.values())weights.put(tier.name(),tier.weight(rarity,false));
            tiers.add(row("rarity",rarity.name(),"label",rarity.label(),"damage",rarity.damageMultiplier(),
                    "speed",rarity.speedMultiplier(),"range",rarity.rangeMultiplier(),"ability",rarity.abilityLevel(),"weights",weights));
            for(UnitType unit:UnitType.values())for(int bonus:new int[]{0,maxBonus}) {
                int limit=rarity==Rarity.MIRACLE?1000:19;
                for(int n=0;n<=limit;n++) {
                    if(n>30 && n!=50 && n!=100 && n!=1000)continue;
                    Defender d=defender(unit,rarity,n,bonus);
                    double expected=unit.profile().at(rarity).damage()*(1+n*(1+bonus/100.0)+(n/5)*.5);
                    require(Math.abs(d.profile().damage()-expected)<=Math.max(1,expected)*1e-12,"Enhancement formula mismatch");
                    stats.add(stat(d,bonus));
                }
                if(rarity!=Rarity.MIRACLE) {
                    Defender d=defender(unit,rarity,19,bonus);
                    double before=d.profile().damage();
                    d.merge();
                    double fresh=unit.profile().at(d.rarity()).damage();
                    promotions.add(row("unit",unit.name(),"from",rarity.name(),"to",d.rarity().name(),"bonus",bonus,
                            "before19Damage",before,"promoted0Damage",d.profile().damage(),"fresh0Damage",fresh,
                            "inheritedDamage",d.profile().damage()-fresh,"vsFresh0",d.profile().damage()/fresh,
                            "dpsStep",raw(d)/(before/unit.profile().at(rarity).intervalTicks()*20)));
                }
            }
        }
        write(output.resolve("stats.json"),stats);
        write(output.resolve("promotions.json"),promotions);
        write(output.resolve("rarities.json"),tiers);
        write(output.resolve("enhancement-factors.json"),factors);
        var traits=TraitCatalog.ALL.stream().filter(e->Set.of(TraitCatalog.Family.ENHANCEMENT,TraitCatalog.Family.DAMAGE,
                TraitCatalog.Family.NORMAL_DAMAGE,TraitCatalog.Family.BOSS_DAMAGE,TraitCatalog.Family.SPEED,
                TraitCatalog.Family.ROLE_DAMAGE,TraitCatalog.Family.CRITICAL).contains(e.family()))
                .map(e->row("id",e.id(),"name",e.name(),"family",e.family().name(),"value",e.value(),
                        "role",e.role()==null?null:e.role().name())).toList();
        write(output.resolve("traits.json"),traits);
        for(UnitType unit:UnitType.values())for(Rarity rarity:Rarity.values())validateSpecials(unit,rarity);
        var trials=new ArrayList<Trial>();
        for(UnitType unit:UnitType.values())for(Rarity rarity:Rarity.values())
            for(String scenario:List.of("single_normal","single_boss","crowd_48"))trials.add(new Trial(unit,rarity,0,scenario));
        for(UnitType unit:UnitType.values())for(String scenario:List.of("single_normal","single_boss","crowd_48"))
            trials.add(new Trial(unit,Rarity.COMMON,9,scenario));
        int threads=Math.min(8,Runtime.getRuntime().availableProcessors());
        System.out.println("Profile rows="+stats.size()+"; engine trials="+trials.size()+"; workers="+threads);
        List<Map<String,Object>> combat;
        try(var pool=new ForkJoinPool(threads)) {combat=pool.submit(()->trials.parallelStream().map(EnhancementAudit::run).toList()).get();}
        write(output.resolve("combat.json"),combat);
        for(UnitType unit:UnitType.values())for(String scenario:List.of("single_normal","single_boss","crowd_48")) {
            double base=findDps(combat,unit,0,scenario),enhanced=findDps(combat,unit,9,scenario);
            require(Math.abs(enhanced/base-10.5)<1e-8,"Combat scaling mismatch: "+unit+" "+scenario);
        }
        var properties=new Properties();
        try(var input=EnhancementAudit.class.getResourceAsStream("/mud-runtime.properties")){properties.load(input);}
        require(args[1].equals(properties.getProperty("commit")),"JAR source commit mismatch");
        Path jar=Path.of(Rarity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String sha256=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        write(output.resolve("integration-checks.json"),integrationChecks());
        write(output.resolve("metadata.json"),row("sourceCommit",args[1],"version",properties.getProperty("version"),"statsRows",stats.size(),
                "engineTrials",trials.size(),"warmupTicks",WARMUP,"measurementTicks",MEASURE,"gameTps",20,
                "maxEnhancementBonus",maxBonus,"combatScalingChecks",72,"specialEffectChecks",792,"jarSha256",sha256));
        System.out.println("Finished; 72 independent common +9 combat scaling checks passed.");
    }

    private static double findDps(List<Map<String,Object>> rows,UnitType unit,int n,String scenario) {
        return ((Number)rows.stream().filter(r->r.get("unit").equals(unit.name()) && r.get("rarity").equals("COMMON")
                && r.get("enhancement").equals(n) && r.get("scenario").equals(scenario)).findFirst().orElseThrow().get("dps")).doubleValue();
    }

    private static Defender defender(UnitType unit,Rarity rarity,int n,int bonus) {
        Defender d=new Defender(new UUID(0,2),OWNER,"audit",unit,rarity,new Cell(0,0),bonus/100.0,0);
        for(int i=0;i<n;i++)d.merge();
        require(d.rarity()==rarity && d.enhancement()==n,"Unexpected promotion");
        return d;
    }

    private static Map<String,Object> stat(Defender d,int bonus) {
        CombatProfile p=d.profile();int level=d.rarity().abilityLevel();
        double normal=raw(d),boss=raw(d),crowd=raw(d);
        switch(d.type().role()) {
            case MELEE_SINGLE -> {normal*=1+.24*level;boss=normal;crowd=normal;}
            case RANGED_SINGLE -> {boss*=1+.5*level;crowd=normal;}
            case SMALL_AREA -> {normal*=1+.3*level;boss=normal;crowd*=10+.3*level;}
            case MULTI_TARGET -> crowd*=Math.min(10,p.targets()+level);
            case MELEE_CLEAVE,LARGE_AREA -> crowd*=10;
        }
        return row("unit",d.type().name(),"name",d.type().label(),"role",d.type().role().name(),"rarity",d.rarity().name(),
                "enhancement",d.enhancement(),"bonus",bonus,"damage",p.damage(),"interval",p.intervalTicks(),
                "range",p.range(),"radius",AttackGeometry.areaRadius(d,p),"targetCap",switch(d.type().role()) {
                    case MULTI_TARGET -> p.targets()+level;
                    case MELEE_SINGLE,RANGED_SINGLE -> 1;
                    default -> null;
                },
                "slow",d.type().role()==AttackRole.MELEE_CLEAVE && level>0?.10+.08*level:0,
                "rawDps",raw(d),"normalDps",normal,"bossDps",boss,"ideal10Dps",crowd);
    }

    private static double raw(Defender d) {return d.profile().damage()*20/d.profile().intervalTicks();}

    private static void validateSpecials(UnitType unit,Rarity rarity) {
        for(String metric:List.of("normalDps","bossDps","ideal10Dps")) {
            Arena arena=new Arena("audit",OWNER,GRID,10,100);
            arena.summon(OWNER,new SummonRoll(unit,rarity),(t,r,c)->new UUID(0,2));
            Defender d=arena.lastSummoned();d.move(new Cell(0,0));
            int count=metric.equals("ideal10Dps")?10:1;
            for(int i=0;i<count;i++) {
                Enemy enemy=new Enemy(new UUID(0,i+100),"audit",EnemyType.ZOMBIE,1e20,2,0,metric.equals("bossDps"));
                for(int k=0;k<30;k++)enemy.advance(k);
                enemy.slow(1-1e-12,Long.MAX_VALUE);arena.addEnemy(enemy);
            }
            CombatEngine engine=new CombatEngine();int last=4*d.profile().intervalTicks();
            for(int tick=0;tick<last;tick++)engine.tick(arena,tick,null);
            double actual=engine.tick(arena,last).stream().mapToDouble(CombatEngine.Hit::damage).sum()*20/d.profile().intervalTicks();
            double expected=((Number)stat(d,0).get(metric)).doubleValue();
            require(Math.abs(actual-expected)<Math.max(1,expected)*1e-10,"Special formula mismatch: "+unit+" "+rarity+" "+metric);
        }
    }

    private static Map<String,Object> run(Trial trial) {
        Arena arena=new Arena("audit",OWNER,GRID,10,100);
        require(arena.summon(OWNER,new SummonRoll(trial.unit,trial.rarity),(t,r,c)->new UUID(0,2))==Arena.Result.OK,"Summon failed");
        Defender d=arena.lastSummoned();for(int i=0;i<trial.enhancement;i++)d.merge();
        Cell cell=CELLS.computeIfAbsent(d.profile().range(),EnhancementAudit::bestCell);d.move(cell);
        int count=trial.scenario.equals("crowd_48")?48:1;
        for(int i=0;i<count;i++) {
            Enemy enemy=new Enemy(new UUID(0,i+100),"audit",EnemyType.ZOMBIE,1e20,2,0,trial.scenario.equals("single_boss"));
            int offset=(int)Math.round(i*840.0/count);
            for(int k=0;k<offset;k++)enemy.advance(k);
            arena.addEnemy(enemy);
        }
        CombatEngine engine=new CombatEngine();double[] damage={0};long[] hits={0};
        CombatEngine.HitSink sink=(defender,enemy,value)->{damage[0]+=value;hits[0]++;};
        for(int tick=0;tick<WARMUP+MEASURE;tick++)engine.tick(arena,tick,tick<WARMUP?null:sink);
        require(arena.activeEnemies().stream().allMatch(Enemy::alive),"Dummy died");
        return row("unit",trial.unit.name(),"rarity",trial.rarity.name(),"enhancement",trial.enhancement,"scenario",trial.scenario,
                "cell",List.of(cell.column(),cell.row()),"coverage",coverage(cell,d.profile().range())/840.0,
                "dps",damage[0]/(MEASURE/20.0),"hits",hits[0]);
    }

    private static Cell bestCell(double range) {
        Cell best=null;int most=-1;
        for(Cell cell:GRID.placementOrder()) {int n=coverage(cell,range);if(n>most){best=cell;most=n;}}
        return best;
    }

    private static int coverage(Cell cell,double range) {
        int n=0;for(int i=0;i<840;i++)if(cell.point().distanceSquared(GRID.route().at(i*.1))<=range*range)n++;
        return n;
    }

    private static Map<String,Object> integrationChecks() {
        long[] sequence={100};
        Arena.Spawner spawner=(t,r,c)->new UUID(0,sequence[0]++);
        Arena arena=new Arena("audit",OWNER,GRID,10000,100);
        for(int i=0;i<10;i++)require(arena.summon(OWNER,new SummonRoll(UnitType.WOLF,Rarity.COMMON),spawner)==Arena.Result.OK,"Summon failed");
        Defender common=arena.lastSummoned();
        require(common.enhancement()==9,"Wrong enhancement");
        arena.toggleMerging(OWNER);
        for(int i=0;i<36;i++)require(arena.summon(OWNER,new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY),spawner)==Arena.Result.OK,"Summon failed");
        boolean deployed=new AutoPlacement(GRID).arrange(arena.units()).containsKey(common.entityId());
        require(!deployed,"Placement priority changed; review conclusion");
        Arena fusion=new Arena("audit",OWNER,GRID,10000,100);
        fusion.summon(OWNER,new SummonRoll(UnitType.WOLF,Rarity.RARE),spawner);
        for(int i=0;i<21;i++)fusion.summon(OWNER,new SummonRoll(UnitType.WOLF,Rarity.COMMON),spawner);
        Defender result=fusion.lastSummoned(),fresh=defender(UnitType.WOLF,Rarity.RARE,1,0);
        require(result.rarity()==Rarity.RARE && result.enhancement()==1,"Fusion result changed");
        return row("common9DeployedAgainst36Legendary0",deployed,"common9RawDps",raw(common),
                "legendary0RawDps",raw(defender(UnitType.WOLF,Rarity.LEGENDARY,0,0)),
                "twentyOneCommonPlusOneRare",stat(result,0),"twoFreshRare",stat(fresh,0),
                "sameLabelDamageRatio",result.profile().damage()/fresh.profile().damage());
    }

    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    private static Map<String,Object> row(Object... pairs) {
        var row=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)row.put((String)pairs[i],pairs[i+1]);return row;
    }
    private static void write(Path path,Object value)throws Exception {Files.writeString(path,json(value),StandardCharsets.UTF_8);}
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map)return "{"+String.join(",",map.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(EnhancementAudit::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}
