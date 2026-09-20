package dev.moma.benchmark;

import dev.moma.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.format.*;

public final class ProgressionSmokePlugin extends JavaPlugin {
    private boolean ran;
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Local fixture only");
        Bukkit.getScheduler().runTaskTimer(this,()->{
            Player player=Bukkit.getPlayerExact("AchievementTest");if(player==null||ran)return;ran=true;
            Bukkit.getScheduler().runTaskLater(this,()->{try{verify(player);}catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"PROGRESSION_SMOKE_FAILED",e);Bukkit.shutdown();}},20);
        },1,5);
    }
    private void verify(Player player)throws Exception {
        Object games=field(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),"games");
        call(games,"start",player);Object session=call(games,"session",player);
        Arena arena=(Arena)field(session,"arena");arena.credit(1000);
        Object adapter=field(games,"entities"),map=field(session,"map");
        for(int i=0;i<20;i++)arena.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL),(t,r,c)->{
            try{return (UUID)call(adapter,"spawnDefender",map,player.getUniqueId(),t,r,c);}catch(Exception e){throw new RuntimeException(e);}
        });
        Defender unit=arena.lastSummoned();require(unit.enhancement()==19,"Primordial +19");
        long seed=0;for(;;seed++){var roll=SummonRoll.draw(new HashRandom(seed),false);if(roll.type()==UnitType.WOLF&&roll.rarity()==Rarity.PRIMORDIAL)break;}
        Field random=session.getClass().getDeclaredField("random");random.setAccessible(true);random.set(session,new HashRandom(seed));
        call(games,"summon",player);
        require(unit.rarity()==Rarity.TRUE_PRIMORDIAL && unit.enhancement()==0,"Service +20 promotion");
        var entity=Bukkit.getEntity(unit.entityId());require(entity!=null,"Entity preserved");
        require(entity.customName().color().equals(NamedTextColor.DARK_RED),"Dark red grade");
        require(entity.customName().decoration(TextDecoration.BOLD)==TextDecoration.State.TRUE,"Bold grade");
        require(unit.profile().damage()==UnitType.WOLF.profile().at(Rarity.PRIMORDIAL).damage()*34,"Plus30 equivalent");
        Campaign campaign=(Campaign)field(session,"campaign");Field elapsed=Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);
        elapsed.setLong(campaign,CampaignRules.standard().preparationTicks()+60000L-1);
        campaign.beforeCombat(arena,s->{try{return (UUID)call(adapter,"spawnEnemy",map,player.getUniqueId(),s.type(),s.boss());}catch(Exception e){throw new RuntimeException(e);}});
        require(campaign.round()==101 && arena.summonCost()==100,"Actual campaign transition");
        double before=arena.coins();call(games,"summon",player);require(arena.coins()==before-100,"Live100gold charge");
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerSwapHandItemsEvent(player,player.getInventory().getItemInOffHand(),player.getInventory().getItemInMainHand()));
        var inventory=player.getOpenInventory().getTopInventory();
        require(inventory.getItem(11).getItemMeta().displayName().toString().contains("100골드"),"GUI price");
        require(inventory.getItem(0).getItemMeta().lore().toString().contains("0.19%"),"GUI advanced odds");
        call(games,"leave",player);
        Files.writeString(Path.of("progression-smoke.json"),"{\"truePrimordialPromotion\":true,\"darkRedBold\":true,\"plus30Equivalent\":true,\"round101Cost\":100,\"guiPriceAndOdds\":true}");
        Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String name,Object...args)throws Exception{
        for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            boolean match=true;Class<?>[] types=m.getParameterTypes();for(int i=0;i<args.length;i++)if(!(types[i]==boolean.class&&args[i] instanceof Boolean)&&!types[i].isInstance(args[i]))match=false;
            if(match){m.setAccessible(true);return m.invoke(o,args);}
        }throw new NoSuchMethodException(name);
    }
}
