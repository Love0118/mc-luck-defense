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

/** Durable progressive checkpoint outbox. Wallet receipts deduplicate interrupted deliveries. */
final class DefenseRewards {
    private record Reward(UUID player,long amount) {}
    private final Path file;
    private final Supplier<NoquiHooks> hooks;
    private final Consumer<String> warning;
    private final Map<String,Reward> pending=new LinkedHashMap<>();
    private final Map<String,Integer> checkpoints=new LinkedHashMap<>();
    private boolean dirty;
    DefenseRewards(MomaPlugin plugin,Supplier<NoquiHooks> hooks)throws IOException {
        this(prepare(plugin),hooks,plugin.getLogger()::warning);
    }
    private static Path prepare(MomaPlugin plugin){
        var config=plugin.getDataFolder().toPath().resolve("rewards.yml");
        if(!Files.exists(config))plugin.saveResource("rewards.yml",false);
        var settings=YamlConfiguration.loadConfiguration(config.toFile());
        if(!settings.getString("formula","milestones-v1").equals("milestones-v1"))throw new IllegalArgumentException("Unsupported starlight reward formula");
        if(settings.contains("starlight-per-round"))plugin.getLogger().info("Legacy starlight-per-round is ignored; rewards use 100-round milestones (10,20,30,...).");
        return plugin.getDataFolder().toPath().resolve("starlight-rewards.json");
    }
    DefenseRewards(Path file,Supplier<NoquiHooks> hooks,Consumer<String> warning)throws IOException {
        this.file=file;this.hooks=hooks;this.warning=warning;
        if(Files.exists(file))try{
            var data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();int version=data.get("version").getAsInt();
            if(version!=1&&version!=2)throw new IllegalArgumentException("Unknown reward outbox version");
            // Existing v1 obligations keep their original transaction IDs. Already-paid wallet balances are never rewritten.
            data.getAsJsonObject("pending").entrySet().forEach(e->{var r=e.getValue().getAsJsonObject();long amount=r.get("amount").getAsLong();
                if(amount<=0)throw new IllegalArgumentException("Invalid reward");pending.put(e.getKey(),new Reward(UUID.fromString(r.get("player").getAsString()),amount));});
            if(version==2)data.getAsJsonObject("checkpoints").entrySet().forEach(e->{int checkpoint=e.getValue().getAsInt();
                if(checkpoint<1||checkpoint>Integer.MAX_VALUE/100)throw new IllegalArgumentException("Invalid checkpoint");checkpoints.put(e.getKey(),checkpoint);});
            dirty=version==1;
        }catch(RuntimeException error){throw new IOException("Invalid starlight reward outbox",error);}
    }
    void reached(UUID session,UUID player,int round) {
        int current=Math.max(0,round/100);String key=session+":"+player;int before=checkpoints.getOrDefault(key,0);
        if(current<=before)return;
        for(int checkpoint=before+1;checkpoint<=current;checkpoint++)
            pending.putIfAbsent("moma-milestone:"+key+":"+checkpoint,new Reward(player,10L*checkpoint));
        checkpoints.put(key,current);dirty=true;flush();
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
        flush();if(dirty)throw new IllegalStateException("별빛 보상 저장에 실패했습니다. 저장소를 복구한 뒤 다시 시도하세요.");
    }
    private boolean save() {
        try{
            Files.createDirectories(file.toAbsolutePath().getParent());var root=new JsonObject();root.addProperty("version",2);var entries=new JsonObject();
            pending.forEach((id,r)->{var value=new JsonObject();value.addProperty("player",r.player().toString());value.addProperty("amount",r.amount());entries.add(id,value);});root.add("pending",entries);
            var progress=new JsonObject();checkpoints.forEach(progress::addProperty);root.add("checkpoints",progress);
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
