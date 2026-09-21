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
            Files.writeString(Path.of("achievement-restart-pass.json"),"{\"persistentStats\":true,\"persistent81Awards\":true,\"noDuplicateCompletions\":true}");
        } else {
            var vanilla=Objects.requireNonNull(Bukkit.getAdvancement(NamespacedKey.minecraft("story/root")));
            for(String criterion:vanilla.getCriteria())player.getAdvancementProgress(vanilla).awardCriteria(criterion);
            require(!player.getAdvancementProgress(vanilla).isDone(),"Vanilla advancement must be blocked");
            int start=completed;
            reached.invoke(service,player,2000);
            for(var rarity:List.of(Rarity.EPIC,Rarity.MYTHIC,Rarity.PRIMORDIAL))
                for(int i=0;i<(rarity==Rarity.EPIC?2500:rarity==Rarity.MYTHIC?1000:100);i++)summoned.invoke(service,player,rarity);
            for(int i=0;i<100;i++)promoted.invoke(service,player);
            Method enhanced=service.getClass().getDeclaredMethod("enhanced",Player.class);enhanced.setAccessible(true);
            for(int i=0;i<50000;i++)enhanced.invoke(service,player);
            Method role=service.getClass().getDeclaredMethod("roleReached",Player.class,Arena.class);role.setAccessible(true);
            Method damage=Arena.class.getDeclaredMethod("recordDamage",AttackRole.class,double.class);damage.setAccessible(true);
            for(AttackRole type:AttackRole.values()) {
                Arena arena=new Arena("fixture",player.getUniqueId(),new Grid(6),30,100);
                damage.invoke(arena,type,100d);role.invoke(service,player,arena);
            }
            Method started=service.getClass().getDeclaredMethod("sessionStarted",Player.class);started.setAccessible(true);
            for(int i=0;i<1000;i++)started.invoke(service,player);
            require(completed-start==81,"Exactly 81 completions, got "+(completed-start));
            reached.invoke(service,player,10);reached.invoke(service,player,2000);
            require(completed-start==81,"Round revisits do not repeat rewards");
            for(var entry:AchievementCatalog.ALL) {
                var advancement=Objects.requireNonNull(Bukkit.getAdvancement(new NamespacedKey("mcluckdefense",entry.id())));
                require(player.getAdvancementProgress(advancement).isDone(),"Awarded "+entry.id());
                require(advancement.getDisplay().doesShowToast(),"Toast enabled");
                require(advancement.getDisplay().frame().name().equals(entry.challenge()?"CHALLENGE":"TASK"),"Native frame");
            }
            Files.writeString(Path.of("achievement-first-pass.json"),"{\"nativeAdvancements\":81,\"exactlyOnce\":true,\"vanillaBlocked\":true,\"challengeFrames\":true}");
        }
        verifyTraits(player,games);
        Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,40);
    }
    private void verifyTraits(Player player,Object games)throws Exception {
        Class<?> selections=Class.forName("dev.moma.paper.TraitSelections");
        Method save=selections.getDeclaredMethod("save",org.bukkit.persistence.PersistentDataContainer.class,List.class);save.setAccessible(true);
        Method load=selections.getDeclaredMethod("load",org.bukkit.persistence.PersistentDataContainer.class);load.setAccessible(true);
        var data=player.getPersistentDataContainer();
        if(Files.exists(Path.of("trait-menu-pass.json")))
            require(((TraitLoadout)load.invoke(null,data)).ids().equals(List.of("round_100","round_250","enhancement_500")),"Trait selection survives restart");
        save.invoke(null,data,List.of("round_100","round_250","enhancement_500"));
        Class<?> lobbyType=Class.forName("dev.moma.paper.Lobby");
        Constructor<?> lobbyConstructor=lobbyType.getDeclaredConstructor(Location.class,double.class);lobbyConstructor.setAccessible(true);
        Object lobby=lobbyConstructor.newInstance(player.getLocation(),256d);
        Class<?> menuType=Class.forName("dev.moma.paper.TraitMenu");
        Constructor<?> menuConstructor=menuType.getDeclaredConstructor(games.getClass(),lobbyType);menuConstructor.setAccessible(true);
        Object menu=menuConstructor.newInstance(games,lobby);
        Method open=menuType.getDeclaredMethod("open",Player.class);open.setAccessible(true);open.invoke(menu,player);
        var inventory=player.getOpenInventory().getTopInventory();
        require(inventory.getSize()==54,"Native trait inventory");
        for(int slot=45;slot<48;slot++)require(inventory.getItem(slot).getType()==Material.NETHER_STAR,"Three equipped trait slots");
        require(inventory.getItem(53)!=null,"Trait catalog pagination");
        player.closeInventory();
        Field toolField=games.getClass().getDeclaredField("tools");toolField.setAccessible(true);Object tools=toolField.get(games);
        Method give=tools.getClass().getDeclaredMethod("giveLobby",Player.class);give.setAccessible(true);give.invoke(tools,player);
        require(player.getInventory().getItem(1).getType()==Material.ENCHANTED_BOOK,"Lobby trait book in slot two");
        var config=CampaignRules.standard();
        Arena arena=new Arena("trait-fixture",player.getUniqueId(),new Grid(6),config.startingCoins(),100,(TraitLoadout)load.invoke(null,data),new HashRandom(1));
        require(arena.coins()==40,"Trait starting gold");
        Arena opening=new Arena("opening-fixture",player.getUniqueId(),new Grid(6),30,100,
                new TraitLoadout(List.of("round_350","session_1000")),new HashRandom(1));
        require(opening.summonWeight(Rarity.MYTHIC)==4000,"Final Mythic opening odds");
        opening.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.MYTHIC),(t,r,c)->UUID.randomUUID());
        require(opening.lastSummoned().rarity()==Rarity.MYTHIC,"Never upgrade Mythic to Primordial");
        require(!opening.openingTraitActive(),"Opening trait ends on raw target hit");
        Files.writeString(Path.of("trait-menu-pass.json"),"{\"nativeInventory\":true,\"pagination\":true,\"threeSlots\":true,\"lobbyBook\":true,\"startingGold\":40}");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
