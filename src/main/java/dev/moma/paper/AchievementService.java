package dev.moma.paper;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import com.google.gson.*;
import dev.moma.core.*;
import dev.moma.core.AchievementCatalog.*;
import java.util.*;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Native advancement tabs/toasts. Challenge frames play the standard client completion sound. */
final class AchievementService implements Listener {
    private final MomaPlugin plugin;
    private final Map<String,Advancement> advancements=new LinkedHashMap<>();
    AchievementService(MomaPlugin plugin) {
        this.plugin=plugin;
        Map<Key,String> definitions=new LinkedHashMap<>();
        Map<Metric,String> parents=new EnumMap<>(Metric.class);
        for(Entry entry:AchievementCatalog.ALL) {
            NamespacedKey key=key(entry.id());
            String parent=parents.getOrDefault(entry.metric(),"round_1");
            if(Bukkit.getAdvancement(key)==null)definitions.put(key,definition(entry,entry.id().equals("round_1")?null:parent));
            parents.put(entry.metric(),entry.id());
        }
        if(!definitions.isEmpty())Bukkit.getUnsafe().loadAdvancements(definitions,false);
        for(Entry entry:AchievementCatalog.ALL)advancements.put(entry.id(),Objects.requireNonNull(Bukkit.getAdvancement(key(entry.id())),entry.id()));
        Bukkit.getPluginManager().registerEvents(this,plugin);
        for(Player player:Bukkit.getOnlinePlayers())restore(player);
    }
    private static NamespacedKey key(String id) { return new NamespacedKey("mcluckdefense",id); }
    static String definition(Entry entry,String parent) {
        JsonObject json=new JsonObject(),display=new JsonObject(),icon=new JsonObject(),criterion=new JsonObject(),criteria=new JsonObject();
        if(parent!=null)json.addProperty("parent","mcluckdefense:"+parent);
        icon.addProperty("id",switch(entry.metric()) {case ROUND->"minecraft:shield";case EPIC->"minecraft:amethyst_shard";case MYTHIC->"minecraft:nether_star";case PRIMORDIAL->"minecraft:dragon_egg";case TRUE_PRIMORDIAL->"minecraft:end_crystal";case MIRACLE->"minecraft:beacon";case ENHANCEMENT->"minecraft:anvil";default->"minecraft:iron_sword";});
        var trait=TraitCatalog.find(entry.id());
        display.add("icon",icon);display.addProperty("title",entry.title());
        display.addProperty("description",entry.description()+(trait==null?"":" · 특성: "+trait.name()));
        display.addProperty("frame",entry.challenge()?"challenge":"task");
        display.addProperty("show_toast",true);display.addProperty("announce_to_chat",true);display.addProperty("hidden",false);
        if(parent==null)display.addProperty("background","minecraft:gui/advancements/backgrounds/stone");
        json.add("display",display);criterion.addProperty("trigger","minecraft:impossible");criteria.add("earned",criterion);json.add("criteria",criteria);
        return json.toString();
    }
    void summoned(Player player,Rarity rarity) {
        Metric metric=switch(rarity) {case EPIC->Metric.EPIC;case MYTHIC->Metric.MYTHIC;case PRIMORDIAL->Metric.PRIMORDIAL;case TRUE_PRIMORDIAL->Metric.TRUE_PRIMORDIAL;case MIRACLE->Metric.MIRACLE;default->null;};
        if(metric!=null)award(player,metric,AchievementStats.summoned(player.getPersistentDataContainer(),metric));
    }
    void reached(Player player,int round) { award(player,Metric.ROUND,AchievementStats.reached(player.getPersistentDataContainer(),round)); }
    void truePrimordialPromoted(Player player) {
        award(player,Metric.TRUE_PRIMORDIAL,AchievementStats.summoned(player.getPersistentDataContainer(),Metric.TRUE_PRIMORDIAL));
    }
    void miracleObtained(Player player) { award(player,Metric.MIRACLE,AchievementStats.summoned(player.getPersistentDataContainer(),Metric.MIRACLE)); }
    void enhanced(Player player) {
        award(player,Metric.ENHANCEMENT,AchievementStats.summoned(player.getPersistentDataContainer(),Metric.ENHANCEMENT));
    }
    void spent(Player player,long amount) {
        award(player,Metric.GOLD_SPENT,AchievementStats.add(player.getPersistentDataContainer(),Metric.GOLD_SPENT,amount));
    }
    void duplicate(Player player) {
        award(player,Metric.DUPLICATE,AchievementStats.summoned(player.getPersistentDataContainer(),Metric.DUPLICATE));
    }
    void sessionStarted(Player player) {
        award(player,Metric.SESSION,AchievementStats.summoned(player.getPersistentDataContainer(),Metric.SESSION));
    }
    void challengesReached(Player player,Arena arena,int round) {
        var data=player.getPersistentDataContainer();
        if(round==150 || round==500)for(Metric metric:Metric.values())if(metric.role() && arena.roleAchievement(metric.attackRole()))
            award(player,metric,AchievementStats.maximum(data,metric,round));
        if(arena.smallForce())award(player,Metric.SMALL_FORCE,AchievementStats.maximum(data,Metric.SMALL_FORCE,round));
        long budget=AchievementCatalog.budget(round);
        if(budget>0 && arena.spentGold()<=budget) {
            Metric metric=Metric.valueOf("BUDGET_"+round);
            award(player,metric,AchievementStats.maximum(data,metric,round));
        }
    }
    void quickCleared(Player player,int streak) {
        award(player,Metric.QUICK_CLEAR,AchievementStats.maximum(player.getPersistentDataContainer(),Metric.QUICK_CLEAR,streak));
    }
    private void award(Player player,Metric metric,long value) {
        for(Entry entry:AchievementCatalog.ALL)if(entry.metric()==metric && value>=entry.target()) {
            var progress=player.getAdvancementProgress(advancements.get(entry.id()));
            if(!progress.isDone()) {
                progress.awardCriteria("earned");
                var trait=TraitCatalog.find(entry.id());
                if(trait!=null)player.sendMessage(Ui.text("&d"+(trait.passive()?"패시브 해금":"특성 해금")+" &f"+trait.name()+" &7· "+trait.description()));
                if(metric==Metric.ROUND && (entry.target()==100 || entry.target()==250 || entry.target()==500 || entry.target()==1500))
                    player.sendMessage(Ui.text("&d특성 슬롯 해금 &f"+TraitCatalog.slots(entry.target())+"개 &7· 로비에서 선택하세요."));
            }
        }
    }
    static boolean vanillaDisplay(Advancement advancement) {
        return advancement.getKey().getNamespace().equals("minecraft") && advancement.getDisplay()!=null;
    }
    private void restore(Player player) {
        // Revoking visible vanilla progress hides its tabs; recipe advancements are preserved.
        Bukkit.advancementIterator().forEachRemaining(advancement->{
            if(vanillaDisplay(advancement)) {
                var progress=player.getAdvancementProgress(advancement);
                for(String criterion:List.copyOf(progress.getAwardedCriteria()))progress.revokeCriteria(criterion);
            }
        });
        for(Metric metric:Metric.values())award(player,metric,AchievementStats.get(player.getPersistentDataContainer(),metric));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void criterion(PlayerAdvancementCriterionGrantEvent event) {
        if(vanillaDisplay(event.getAdvancement()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void done(PlayerAdvancementDoneEvent event) {
        if(vanillaDisplay(event.getAdvancement()))event.message(null);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin,()->{if(event.getPlayer().isOnline())restore(event.getPlayer());});
    }
}
