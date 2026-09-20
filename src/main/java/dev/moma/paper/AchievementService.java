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
        icon.addProperty("id",switch(entry.metric()) {case ROUND->"minecraft:shield";case EPIC->"minecraft:amethyst_shard";case MYTHIC->"minecraft:nether_star";case PRIMORDIAL->"minecraft:dragon_egg";});
        display.add("icon",icon);display.addProperty("title",entry.title());display.addProperty("description",entry.description());
        display.addProperty("frame",entry.challenge()?"challenge":"task");
        display.addProperty("show_toast",true);display.addProperty("announce_to_chat",true);display.addProperty("hidden",false);
        if(parent==null)display.addProperty("background","minecraft:gui/advancements/backgrounds/stone");
        json.add("display",display);criterion.addProperty("trigger","minecraft:impossible");criteria.add("earned",criterion);json.add("criteria",criteria);
        return json.toString();
    }
    void summoned(Player player,Rarity rarity) {
        Metric metric=switch(rarity) {case EPIC->Metric.EPIC;case MYTHIC->Metric.MYTHIC;case PRIMORDIAL->Metric.PRIMORDIAL;default->null;};
        if(metric!=null)award(player,metric,AchievementStats.summoned(player.getPersistentDataContainer(),metric));
    }
    void reached(Player player,int round) { award(player,Metric.ROUND,AchievementStats.reached(player.getPersistentDataContainer(),round)); }
    private void award(Player player,Metric metric,long value) {
        for(Entry entry:AchievementCatalog.ALL)if(entry.metric()==metric && value>=entry.target()) {
            var progress=player.getAdvancementProgress(advancements.get(entry.id()));
            if(!progress.isDone())progress.awardCriteria("earned");
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
