package dev.moma.benchmark;

import com.google.gson.Gson;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class LeaderboardSmokePlugin extends JavaPlugin {
    private Object board;
    private Player first,second;
    private int phase,ticks;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try{tick();}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"LEADERBOARD_FAILED",e);Bukkit.shutdown();}
        },1,1);
    }
    private void tick()throws Exception {
        if(++ticks>1200)throw new IllegalStateException("Test timeout phase "+phase);
        if(phase==0) {
            first=Bukkit.getPlayerExact("RankFirst");second=Bukkit.getPlayerExact("RankSecond");
            if(first==null || second==null)return;
            board=field(field(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),"games"),"leaderboard");
            if(board==null)throw new IllegalStateException("No leaderboard");
            var next=(Interaction)field(board,"nextButton");
            Location near=next.getLocation().clone().add(0,-.6,-1);
            first.teleport(near);second.teleport(near.clone().add(.5,0,0));
            phase=1;ticks=0;
        } else if(phase==1 && ticks>=40) {
            var views=(Map<?,?>)field(board,"views");
            var firstDisplay=(TextDisplay)field(views.get(first.getUniqueId()),"display");
            var secondDisplay=(TextDisplay)field(views.get(second.getUniqueId()),"display");
            require(first.canSee(firstDisplay)&&!first.canSee(secondDisplay),"First visibility");
            require(second.canSee(secondDisplay)&&!second.canSee(firstDisplay),"Second visibility");
            Files.writeString(Path.of("leaderboard-ids.json"),new Gson().toJson(Map.of(
                    "first",firstDisplay.getEntityId(),"second",secondDisplay.getEntityId(),
                    "previous",((Entity)field(board,"previousButton")).getEntityId(),
                    "next",((Entity)field(board,"nextButton")).getEntityId())));
            phase=2;ticks=0;
        } else if(phase==2 && Files.exists(Path.of("client-clicked.flag")) && ticks>=100) {
            var views=(Map<?,?>)field(board,"views");
            require((int)field(views.get(first.getUniqueId()),"page")==1,"First should be page2");
            require((int)field(views.get(second.getUniqueId()),"page")==0,"Second should stay page1");
            ((TextDisplay)field(views.get(first.getUniqueId()),"display")).remove();
            phase=3;ticks=0;
        } else if(phase==3 && ticks>=40) {
            var views=(Map<?,?>)field(board,"views");
            TextDisplay replacement=(TextDisplay)field(views.get(first.getUniqueId()),"display");
            require(replacement.isValid()&&first.canSee(replacement)&&!second.canSee(replacement),"Private recreation");
            require((int)field(views.get(first.getUniqueId()),"page")==1,"Recreation preserves page");
            Files.writeString(Path.of("leaderboard-passed.json"),"{\"privateVisibility\":true,\"wireClickChangesOnlyActor\":true,\"recreationKeepsPage\":true}");
            Bukkit.shutdown();
        }
    }
    private static Object field(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
