package dev.moma.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One personal best per UUID, with stable tie ordering. */
public final class RoundRecords {
    public record Entry(UUID player,String name,int round) {}
    private final Map<UUID,Entry> entries=new HashMap<>();
    public boolean record(UUID player,String name,int round) {
        if(round<1)return false;
        Entry old=entries.get(player);
        if(old!=null && old.round()>=round && old.name().equals(name))return false;
        entries.put(player,new Entry(player,name,Math.max(round,old==null?0:old.round())));return true;
    }
    public List<Entry> top(int count) {
        return entries.values().stream().sorted(Comparator.comparingInt(Entry::round).reversed()
                .thenComparing(e->e.player().toString())).limit(count).toList();
    }
    public List<Entry> snapshot(){return List.copyOf(entries.values());}
    public static RoundRecords load(Path path)throws IOException {
        RoundRecords result=new RoundRecords();if(!Files.exists(path))return result;
        Properties properties=new Properties();try(var in=Files.newBufferedReader(path)){properties.load(in);}
        for(String key:properties.stringPropertyNames())if(key.endsWith(".round")) {
            String id=key.substring(0,key.length()-6);
            try{result.record(UUID.fromString(id),properties.getProperty(id+".name",id),Integer.parseInt(properties.getProperty(key)));}
            catch(IllegalArgumentException invalid){throw new IOException("Invalid leaderboard record: "+id,invalid);}
        }
        return result;
    }
    public static void save(Path path,List<Entry> entries)throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());Properties properties=new Properties();
        for(Entry entry:entries){properties.setProperty(entry.player()+".round",Integer.toString(entry.round()));properties.setProperty(entry.player()+".name",entry.name());}
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try(var out=Files.newBufferedWriter(temp)){properties.store(out,"Highest reached rounds");}
        try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ignored){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
}
