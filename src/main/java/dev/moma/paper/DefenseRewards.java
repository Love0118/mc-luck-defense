package dev.moma.paper;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Durable outbox; wallet transactions deduplicate a delivery interrupted by reload or shutdown. */
final class DefenseRewards {
    private record Reward(UUID player,long amount) {}
    private final Path file;
    private final long perRound;
    private final Supplier<NoquiHooks> hooks;
    private final Consumer<String> warning;
    private final Map<String,Reward> pending=new LinkedHashMap<>();
    private boolean dirty;
    DefenseRewards(MomaPlugin plugin,Supplier<NoquiHooks> hooks)throws IOException {
        this(prepare(plugin),rate(plugin),hooks,plugin.getLogger()::warning);
    }
    private static Path prepare(MomaPlugin plugin){
        if(!Files.exists(plugin.getDataFolder().toPath().resolve("rewards.yml")))plugin.saveResource("rewards.yml",false);
        return plugin.getDataFolder().toPath().resolve("starlight-rewards.json");
    }
    private static long rate(MomaPlugin plugin){return YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("rewards.yml").toFile()).getLong("starlight-per-round",1);}
    DefenseRewards(Path file,long perRound,Supplier<NoquiHooks> hooks,Consumer<String> warning)throws IOException {
        if(perRound<0||perRound>1_000_000)throw new IllegalArgumentException("starlight-per-round must be 0..1000000");
        this.file=file;this.perRound=perRound;this.hooks=hooks;this.warning=warning;
        if(Files.exists(file))try{
            var data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(data.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unknown reward outbox version");
            data.getAsJsonObject("pending").entrySet().forEach(e->{var r=e.getValue().getAsJsonObject();long amount=r.get("amount").getAsLong();
                if(amount<=0)throw new IllegalArgumentException("Invalid reward");pending.put(e.getKey(),new Reward(UUID.fromString(r.get("player").getAsString()),amount));});
        }catch(RuntimeException error){throw new IOException("Invalid starlight reward outbox",error);}
    }
    void award(UUID session,UUID player,int highestRound) {
        if(highestRound<=0||perRound==0)return;
        String transaction="moma:"+session+":"+player;
        pending.putIfAbsent(transaction,new Reward(player,Math.multiplyExact((long)highestRound,perRound)));dirty=true;flush();
    }
    void flush() {
        if(dirty&&!save())return;
        boolean delivered=false;
        for(var it=pending.entrySet().iterator();it.hasNext();){var entry=it.next();var reward=entry.getValue();
            try{if(hooks.get().rewardStarlight(reward.player(),entry.getKey(),reward.amount())){it.remove();delivered=true;}}
            catch(RuntimeException error){warning.accept("Starlight reward delivery pending; durable wallet transaction will retry.");break;}
        }
        if(delivered){dirty=true;save();}
    }
    void checkReloadReady() {
        flush();
        if(dirty)throw new IllegalStateException("별빛 보상 저장에 실패했습니다. 저장소를 복구한 뒤 다시 시도하세요.");
    }
    private boolean save() {
        try{
            Files.createDirectories(file.toAbsolutePath().getParent());var root=new JsonObject();root.addProperty("version",1);var entries=new JsonObject();
            pending.forEach((id,r)->{var value=new JsonObject();value.addProperty("player",r.player().toString());value.addProperty("amount",r.amount());entries.add(id,value);});root.add("pending",entries);
            Path temp=file.resolveSibling(file.getFileName()+".tmp");
            try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                ByteBuffer data=StandardCharsets.UTF_8.encode(root.toString());while(data.hasRemaining())channel.write(data);channel.force(true);
            }
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;return true;
        }catch(IOException error){warning.accept("Starlight reward outbox save failed; keeping pending rewards for retry.");return false;}
    }
}
