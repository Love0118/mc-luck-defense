package dev.moma.sim;

import dev.moma.core.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Independent games share a bounded worker queue; combat itself remains single-threaded. */
public final class ProgressionBenchmarkMain {
    private static final int[] CHECKPOINTS={30,60,100,200,300,400,500,600,700,800,900,1000,1250,1500,1750,2000,2100,2250,2400,2500,3000,4000,5000,7500,10000};
    record Options(int runs,long seed,Path output,List<String> cases,int threads,int cap,String curve,Double healthPower,Double pressureBend,boolean resume) {
        static Options parse(String[] args) {
            int runs=args.length>0?Integer.parseInt(args[0]):256;
            long seed=args.length>1?Long.parseLong(args[1]):28000000;
            Path output=Path.of(args.length>2?args[2]:"target/progression");
            List<String> cases=List.of("none","top_fusion");
            int cursor=3,threads=Runtime.getRuntime().availableProcessors(),cap=2500;String curve=null;Double power=null,bend=null;boolean resume=false;
            if(args.length>cursor && !args[cursor].startsWith("--"))cases=List.of(args[cursor++].split(","));
            while(cursor<args.length) {
                String option=args[cursor++];
                if(option.equals("--resume")){resume=true;continue;}
                if(cursor==args.length)throw new IllegalArgumentException("Missing value: "+option);
                String value=args[cursor++];
                switch(option) {
                    case "--threads" -> threads=Integer.parseInt(value);
                    case "--cap" -> cap=Integer.parseInt(value);
                    case "--health-curve" -> curve=value;
                    case "--health-power" -> power=Double.valueOf(value);
                    case "--pressure-bend" -> bend=Double.valueOf(value);
                    default -> throw new IllegalArgumentException("Unknown option: "+option);
                }
            }
            if(runs<1 || runs>100000 || threads<1 || threads>256 || cap<1 || cap>10000)
                throw new IllegalArgumentException("Invalid runs, threads or round cap");
            Math.addExact(seed,runs-1L);
            if(cases.isEmpty() || new HashSet<>(cases).size()!=cases.size() || !loadouts().keySet().containsAll(cases))
                throw new IllegalArgumentException("Unknown or duplicate scenario");
            return new Options(runs,seed,output,cases,threads,cap,curve,power,bend,resume);
        }
    }
    static Map<String,TraitLoadout> loadouts() {
        var result=new LinkedHashMap<String,TraitLoadout>();result.put("none",TraitLoadout.EMPTY);
        List<String> passives=TraitCatalog.ALL.stream().filter(TraitCatalog.Entry::passive).map(TraitCatalog.Entry::id).toList();
        var growth=new ArrayList<>(passives);growth.addAll(List.of("miracle_100","duplicate_10000","gold_spent_10000000"));
        result.put("top_growth",new TraitLoadout(growth));
        var combat=new ArrayList<>(passives);combat.addAll(List.of("round_10000","mythic_1000","miracle_100"));
        result.put("top_combat",new TraitLoadout(combat));
        var fusion=new ArrayList<>(passives);fusion.addAll(List.of("round_10000","duplicate_10000","miracle_100"));
        result.put("top_fusion",new TraitLoadout(fusion));
        var incomeDamage=new ArrayList<>(passives);incomeDamage.addAll(List.of("round_10000","duplicate_10000","gold_spent_10000000"));
        result.put("top_income_damage",new TraitLoadout(incomeDamage));
        result.put("equipped",new TraitLoadout(List.of("round_350","session_100","session_250","session_500",
                "enhancement_2000","duplicate_500","gold_spent_100000")));
        return result;
    }
    private static final class Cohort implements AutoCloseable {
        final String name;
        final TraitLoadout traits;
        final String[] rows;
        final BufferedWriter journal;
        int done;
        Cohort(String name,TraitLoadout traits,Options options)throws IOException {
            this.name=name;this.traits=traits;rows=new String[options.runs()];
            Path partial=options.output().resolve(name+".partial.jsonl");
            if(Files.exists(partial)) {
                if(!options.resume())throw new IllegalArgumentException("Output already exists; use --resume or a new directory");
                byte[] bytes=Files.readAllBytes(partial);int end=bytes.length;
                while(end>0 && bytes[end-1]!='\n')end--;
                for(String row:new String(bytes,0,end,StandardCharsets.UTF_8).split("\n"))if(!row.isEmpty())store(row,options.seed());
                try(var channel=java.nio.channels.FileChannel.open(partial,StandardOpenOption.WRITE)){channel.truncate(end);}
            }
            journal=Files.newBufferedWriter(partial,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            Files.writeString(options.output().resolve(name+"-traits.txt"),String.join(",",traits.allIds()));
        }
        void store(String row,long first) {
            long index=number(row,"seed")-first;
            if(index<0 || index>=rows.length || !row.endsWith("]}") || rows[(int)index]!=null)
                throw new IllegalArgumentException("Invalid or duplicate checkpoint row in "+name);
            rows[(int)index]=row;done++;
        }
        void add(String row,long first)throws IOException {store(row,first);journal.write(row);journal.write('\n');}
        @Override public void close()throws IOException {journal.close();}
    }
    private record Completed(Cohort cohort,String row) {}
    public static void main(String[] args)throws Exception {
        Options options=Options.parse(args);CampaignRules rules=CampaignRules.standard();
        if(options.curve()!=null)rules=rules.withHealthCurve(HealthCurve.parse(options.curve()));
        if(options.healthPower()!=null)rules=rules.withEndlessHealthPower(options.healthPower());
        if(options.pressureBend()!=null)rules=rules.withEndlessPressureBend(options.pressureBend());
        run(options,rules);
    }
    static void run(Options options,CampaignRules rules)throws Exception {
        Files.createDirectories(options.output());
        String configuration="runs="+options.runs()+"\nseed="+options.seed()+"\nroundCap="+options.cap()
                +"\nbatchTicks=500\nrequestedVirtualSpeed=unlimited\nscenarios="+String.join(",",options.cases())
                +"\nhealthCurve="+rules.healthCurve().specification()+"\nrules="+rules+"\nbuild="+buildFingerprint()+"\n"
                +"policy=reserve, grade-first placement, four transactions per game tick\n";
        Path config=options.output().resolve("rules.txt");
        if(Files.exists(config) && (!options.resume() || !Files.readString(config).equals(configuration)))
            throw new IllegalArgumentException("Existing run differs; choose a new output directory");
        Files.writeString(config,configuration);
        var cohorts=new ArrayList<Cohort>();var executor=(ThreadPoolExecutor)Executors.newFixedThreadPool(options.threads());
        var completed=new ExecutorCompletionService<Completed>(executor);
        Path metricsPath=options.output().resolve("performance.csv");
        try(var metrics=Files.newBufferedWriter(metricsPath,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
            if(Files.size(metricsPath)==0)metrics.write("elapsed_seconds,completed,new_completed,active_workers,threads,process_cpu_percent,games_per_second\n");
            Map<String,TraitLoadout> loadouts=loadouts();
            for(String name:options.cases())cohorts.add(new Cohort(name,loadouts.get(name),options));
            int resumed=cohorts.stream().mapToInt(c->c.done).sum(),done=resumed,inFlight=0,cursor=0,total=options.runs()*cohorts.size();
            long started=System.nanoTime(),lastReport=started,cpuStarted=cpuTime();
            System.out.println("threads="+options.threads()+" processors="+Runtime.getRuntime().availableProcessors()+" games="+total+" resumed="+resumed);
            while(done<total) {
                while(inFlight<options.threads()*2 && cursor<total) {
                    int index=cursor/cohorts.size();Cohort cohort=cohorts.get(cursor++%cohorts.size());
                    if(cohort.rows[index]!=null)continue;
                    long seed=options.seed()+index;
                    completed.submit(()->new Completed(cohort,EndlessSimulatorMain.run(seed,options.cap(),rules,cohort.traits,500)));inFlight++;
                }
                Future<Completed> future=completed.poll(1,TimeUnit.SECONDS);
                if(future!=null){Completed item=future.get();item.cohort().add(item.row(),options.seed());inFlight--;done++;}
                long now=System.nanoTime();
                if(now-lastReport>=5_000_000_000L || done==total) {
                    double elapsed=(now-started)/1e9;
                    double cpu=(cpuTime()-cpuStarted)/(double)(now-started)/Runtime.getRuntime().availableProcessors()*100;
                    String line=String.format(Locale.ROOT,"%.3f,%d,%d,%d,%d,%.2f,%.3f",elapsed,done,done-resumed,executor.getActiveCount(),options.threads(),cpu,(done-resumed)/elapsed);
                    metrics.write(line);metrics.write('\n');metrics.flush();
                    for(Cohort cohort:cohorts)cohort.journal.flush();
                    System.out.println("completed="+done+"/"+total+" cpu="+String.format(Locale.ROOT,"%.1f%%",cpu)+" games/s="+String.format(Locale.ROOT,"%.1f",(done-resumed)/elapsed));
                    lastReport=now;
                }
            }
            double elapsed=(System.nanoTime()-started)/1e9;
            finish(options,cohorts,elapsed,resumed);
        } finally {
            executor.shutdownNow();
            for(Cohort cohort:cohorts)cohort.close();
        }
    }
    private static void finish(Options options,List<Cohort> cohorts,double elapsed,int resumed)throws IOException {
        var summary=new ArrayList<String>();String header="scenario,runs,wall_seconds,simulated_seconds,effective_speed,mean_round";
        int[] checkpoints=Arrays.stream(CHECKPOINTS).filter(r->r<=options.cap()).toArray();
        for(int round:checkpoints)header+=",reach"+round;
        summary.add(header);
        for(Cohort cohort:cohorts) {
            long ticks=0,totalRounds=0;int[] counts=new int[checkpoints.length];
            try(var output=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(options.output().resolve(cohort.name+".jsonl.gz"))),StandardCharsets.UTF_8))) {
                for(String row:cohort.rows) {
                    output.write(row);output.write('\n');long round=number(row,"round");ticks+=number(row,"ticks");totalRounds+=round;
                    for(int i=0;i<counts.length;i++)if(round>=checkpoints[i])counts[i]++;
                }
            }
            // Resume timing only covers the new process; do not imply old games ran again in that time.
            String line=cohort.name+","+options.runs()+","+elapsed+","+ticks/20.0+","+(resumed==0?ticks/20.0/elapsed:"")+","+totalRounds/(double)options.runs();
            for(int count:counts)line+=","+count;
            summary.add(line);System.out.println(line);
        }
        Files.write(options.output().resolve("summary.csv"),summary,StandardCharsets.UTF_8);
    }
    private static long cpuTime() {
        return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }
    private static String buildFingerprint()throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        Path location=Path.of(ProgressionBenchmarkMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(Files.isRegularFile(location))hash.update(Files.readAllBytes(location));
        else try(var files=Files.walk(location)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                hash.update(location.relativize(file).toString().getBytes(StandardCharsets.UTF_8));hash.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static long number(String row,String name) {
        String prefix="\""+name+"\":";int start=row.indexOf(prefix)+prefix.length();
        return Long.parseLong(row.substring(start,row.indexOf(',',start)));
    }
}
