package dev.moma.paper;

import dev.moma.core.RoundRecords;
import java.nio.file.Path;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;

final class RoundLeaderboard implements AutoCloseable {
    private static final String TAG="mud_round_leaderboard";
    private final MomaPlugin plugin;
    private final RoundRecords records;
    private final Path file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mud-rank-save").factory());
    private final TextDisplay display;
    private volatile boolean dirty;
    RoundLeaderboard(MomaPlugin plugin,Lobby lobby)throws java.io.IOException {
        this.plugin=plugin;file=plugin.getDataFolder().toPath().resolve("round-records.properties");records=RoundRecords.load(file);
        Location location=location(lobby.spawn());
        location.getChunk().load();
        for(Entity entity:location.getWorld().getEntities())if(entity.getScoreboardTags().contains(TAG))entity.remove();
        display=location.getWorld().spawn(location,TextDisplay.class,text->{
            text.addScoreboardTag(TAG);text.setPersistent(false);text.setGravity(false);
            text.setBillboard(Display.Billboard.CENTER);text.setAlignment(TextDisplay.TextAlignment.CENTER);
            text.setLineWidth(400);text.setSeeThrough(false);text.setShadowed(true);
            text.setBackgroundColor(Color.fromARGB(180,12,16,24));
        });
        render();
        Bukkit.getScheduler().runTaskTimer(plugin,this::flush,100,100);
    }
    static Location location(Location spawn) {
        Location horizontal=spawn.clone();horizontal.setPitch(0);
        return spawn.clone().add(horizontal.getDirection().multiply(5)).add(0,2.8,0);
    }
    void record(Player player,int round) {
        if(records.record(player.getUniqueId(),player.getName(),round)){dirty=true;render();}
    }
    private void render() {
        Component text=Ui.text("&6&l최고 라운드 TOP 10");var top=records.top(10);
        for(int i=0;i<10;i++) {
            text=text.append(Component.newline()).append(Component.text((i+1)+". ",NamedTextColor.YELLOW));
            text=text.append(i<top.size()?Component.text(top.get(i).name()+"  ·  R"+top.get(i).round(),NamedTextColor.WHITE):Component.text("—",NamedTextColor.GRAY));
        }
        display.text(text);
    }
    private void flush() {
        if(!dirty)return;dirty=false;var snapshot=records.snapshot();
        writer.execute(()->{try{RoundRecords.save(file,snapshot);}catch(Exception e){dirty=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Leaderboard save failed",e);}});
    }
    @Override public void close() {
        flush();writer.shutdown();
        try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning("Leaderboard writer still finishing");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        display.remove();
    }
}
