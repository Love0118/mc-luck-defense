package dev.moma.benchmark;

import dev.moma.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class AchievementSmokePlugin extends JavaPlugin implements Listener {
    private int completed;
    private boolean ran;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Local fixture only");
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            Player player=Bukkit.getPlayerExact("AchievementTest");
            if(player==null || ran)return;ran=true;
            Bukkit.getScheduler().runTaskLater(this,()->{
                try { verify(player); } catch(Throwable error) { getLogger().log(java.util.logging.Level.SEVERE,"ACHIEVEMENT_SMOKE_FAILED",error);Bukkit.shutdown(); }
            },20);
        },1,5);
    }
    @EventHandler public void done(PlayerAdvancementDoneEvent event) {
        if(event.getAdvancement().getKey().getNamespace().equals("mcluckdefense"))completed++;
    }
    private void verify(Player player) throws Exception {
        var plugin=Bukkit.getPluginManager().getPlugin("MCLuckDefense");
        Field field=plugin.getClass().getDeclaredField("games");field.setAccessible(true);Object games=field.get(plugin);
        field=games.getClass().getDeclaredField("achievements");field.setAccessible(true);Object service=field.get(games);
        Method reached=service.getClass().getDeclaredMethod("reached",Player.class,int.class);reached.setAccessible(true);
        Method summoned=service.getClass().getDeclaredMethod("summoned",Player.class,Rarity.class);summoned.setAccessible(true);
        Method promoted=service.getClass().getDeclaredMethod("truePrimordialPromoted",Player.class);promoted.setAccessible(true);
        if(Files.exists(Path.of("achievement-first-pass.json"))) {
            require(player.getPersistentDataContainer().get(new NamespacedKey("mcluckdefense","achievement_epic"),PersistentDataType.LONG)==2500L,"Persistent Epic count");
            require(player.getPersistentDataContainer().get(new NamespacedKey("mcluckdefense","achievement_true_primordial"),PersistentDataType.LONG)==100L,"Persistent True Primordial promotions");
            for(var entry:AchievementCatalog.ALL)require(player.getAdvancementProgress(Bukkit.getAdvancement(new NamespacedKey("mcluckdefense",entry.id()))).isDone(),"Persisted "+entry.id());
            require(completed==0,"Reconnect must not reannounce old completions");
            Files.writeString(Path.of("achievement-restart-pass.json"),"{\"persistentStats\":true,\"persistent60Awards\":true,\"noDuplicateCompletions\":true}");
        } else {
            var vanilla=Objects.requireNonNull(Bukkit.getAdvancement(NamespacedKey.minecraft("story/root")));
            for(String criterion:vanilla.getCriteria())player.getAdvancementProgress(vanilla).awardCriteria(criterion);
            require(!player.getAdvancementProgress(vanilla).isDone(),"Vanilla advancement must be blocked");
            int start=completed;
            reached.invoke(service,player,2000);
            for(var rarity:List.of(Rarity.EPIC,Rarity.MYTHIC,Rarity.PRIMORDIAL))
                for(int i=0;i<(rarity==Rarity.EPIC?2500:rarity==Rarity.MYTHIC?1000:100);i++)summoned.invoke(service,player,rarity);
            for(int i=0;i<100;i++)promoted.invoke(service,player);
            require(completed-start==60,"Exactly sixty completions, got "+(completed-start));
            reached.invoke(service,player,10);reached.invoke(service,player,2000);
            require(completed-start==60,"Round revisits do not repeat rewards");
            for(var entry:AchievementCatalog.ALL) {
                var advancement=Objects.requireNonNull(Bukkit.getAdvancement(new NamespacedKey("mcluckdefense",entry.id())));
                require(player.getAdvancementProgress(advancement).isDone(),"Awarded "+entry.id());
                require(advancement.getDisplay().doesShowToast(),"Toast enabled");
                require(advancement.getDisplay().frame().name().equals(entry.challenge()?"CHALLENGE":"TASK"),"Native frame");
            }
            Files.writeString(Path.of("achievement-first-pass.json"),"{\"nativeAdvancements\":60,\"exactlyOnce\":true,\"vanillaBlocked\":true,\"challengeFrames\":true}");
        }
        Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,40);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
