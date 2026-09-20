package dev.moma.paper;

import dev.moma.core.RoundRecords;
import java.nio.file.Path;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

final class RoundLeaderboard implements AutoCloseable {
    private static final String TAG="mud_round_leaderboard";
    private final MomaPlugin plugin;
    private final RoundRecords records;
    private final Path file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mud-rank-save").factory());
    private final Location location;
    private final Chunk chunk;
    private final BukkitTask maintenance;
    private TextDisplay display;
    private Component text;
    private boolean closed;
    private volatile boolean dirty;
    RoundLeaderboard(MomaPlugin plugin,Lobby lobby)throws java.io.IOException {
        this.plugin=plugin;file=plugin.getDataFolder().toPath().resolve("round-records.properties");records=RoundRecords.load(file);
        location=location(lobby.spawn());chunk=location.getChunk();
        chunk.addPluginChunkTicket(plugin);
        try {
            for(Entity entity:location.getWorld().getEntities())if(entity.getScoreboardTags().contains(TAG))entity.remove();
            render();
            maintenance=Bukkit.getScheduler().runTaskTimer(plugin,()->{ensureDisplay();flush();},100,100);
        } catch(RuntimeException error) {
            if(display!=null)display.remove();chunk.removePluginChunkTicket(plugin);writer.shutdown();throw error;
        }
    }
    static Location location(Location spawn) {
        Location horizontal=spawn.clone();horizontal.setPitch(0);
        return spawn.clone().add(horizontal.getDirection().multiply(5)).add(0,2.8,0);
    }
    void record(Player player,int round) {
        if(records.record(player.getUniqueId(),player.getName(),round)){dirty=true;render();}
    }
    private void render() {
        text=Ui.text("&6&l최고 라운드 TOP 10");var top=records.top(10);
        for(int i=0;i<10;i++) {
            text=text.append(Component.newline()).append(Component.text((i+1)+". ",NamedTextColor.YELLOW));
            text=text.append(i<top.size()?Component.text(top.get(i).name()+"  ·  R"+top.get(i).round(),NamedTextColor.WHITE):Component.text("—",NamedTextColor.GRAY));
        }
        ensureDisplay();display.text(text);
    }
    private void ensureDisplay() {
        if(closed || display!=null && display.isValid())return;
        // Non-persistent displays do not survive chunk unload or entity cleanup.
        display=location.getWorld().spawn(location,TextDisplay.class,entity->{
            entity.addScoreboardTag(TAG);entity.setPersistent(false);entity.setGravity(false);
            entity.setBillboard(Display.Billboard.CENTER);entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(400);entity.setSeeThrough(false);entity.setShadowed(true);
            entity.setBackgroundColor(Color.fromARGB(180,12,16,24));entity.text(text);
        });
    }
    private void flush() {
        if(!dirty)return;dirty=false;var snapshot=records.snapshot();
        writer.execute(()->{try{RoundRecords.save(file,snapshot);}catch(Exception e){dirty=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Leaderboard save failed",e);}});
    }
    @Override public void close() {
        if(closed)return;closed=true;maintenance.cancel();
        flush();writer.shutdown();
        try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning("Leaderboard writer still finishing");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        display.remove();
        chunk.removePluginChunkTicket(plugin);
    }
}
