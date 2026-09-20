package dev.moma.benchmark;

import dev.moma.bgm.*;
import dev.moma.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Local protocol fixture: clients verify the ZIP hash and simulate resource reload acknowledgements. */
public final class BgmSmokePlugin extends JavaPlugin implements Listener {
    private Object games,bgm;
    private Player owner,viewer;
    private int stage,ticks;
    private final Set<UUID> ownerReady=new HashSet<>(),viewerReady=new HashSet<>();
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        try {
            games=get(Bukkit.getPluginManager().getPlugin("MCLuckDefense"),"games");bgm=get(games,"bgm");
            if(bgm==null)throw new IllegalStateException("Missing BGM service");
        } catch(Exception e){throw new RuntimeException(e);}
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try{tick();}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"BGM_SMOKE_FAILED",e);Bukkit.shutdown();}
        },1,1);
    }
    @EventHandler public void status(PlayerResourcePackStatusEvent event) {
        if(event.getStatus()==PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            if(event.getPlayer().equals(owner))ownerReady.add(event.getID());
            if(event.getPlayer().equals(viewer))viewerReady.add(event.getID());
        }
    }
    private void tick() throws Exception {
        if(stage==0) {
            owner=Bukkit.getPlayerExact("MudBgmOwner");viewer=Bukkit.getPlayerExact("MudBgmViewer");
            if(owner==null || viewer==null || !(boolean)get(bgm,"catalogLoaded"))return;
            if(++ticks<40)return;
            List<Track> tracks=new ArrayList<>();
            for(int i=0;i<3;i++) {
                String id="fixture"+i;
                tracks.add(new Track(id,owner.getUniqueId(),owner.getName(),id,"",
                        "http://127.0.0.1:25588/"+id+".zip",Files.readString(Path.of("packs",id+".sha1")).trim(),6));
            }
            set(bgm,"tracks",List.copyOf(tracks));
            set(bgm,"playlists",Map.of(owner.getUniqueId(),new BgmPlaylist(tracks.stream().map(Track::id).toList(),BgmTimeline.Mode.MEDLEY,"fixture0")));
            call(games,"start",owner);call(games,"spectate",viewer,owner.getName());
            checkFusionAndEndless();
            stage=1;ticks=0;
        } else if(stage==1) {
            if(++ticks>700)throw new IllegalStateException("Pack ACK timeout");
            if(ownerReady.size()!=3 || viewerReady.size()!=3)return;
            stage=2;ticks=0;
        } else if(stage==2 && ++ticks>=100) {
            call(games,"leave",viewer);stage=3;ticks=0;
        } else if(stage==3 && ++ticks>=40) {
            call(games,"spectate",viewer,owner.getName());stage=4;ticks=0;
        } else if(stage==4 && ++ticks>=100) {
            boolean board=owner.getServer().getWorld("mud_lobby").getEntities().stream()
                    .filter(e->e instanceof org.bukkit.entity.TextDisplay).map(e->(org.bukkit.entity.TextDisplay)e)
                    .anyMatch(e->net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.text()).contains("R101"));
            if(!board)throw new IllegalStateException("Leaderboard missing reached round101");
            Files.writeString(Path.of("bgm-via-server-passed.json"),"{\"translatedUuidAcks\":6,\"spectatorReentry\":true,\"fusionPlus5\":true,\"mergedSale\":true,\"endlessRound101\":true,\"leaderboard\":true}");
            Bukkit.shutdown();
        }
    }
    private void checkFusionAndEndless()throws Exception {
        Object session=call(games,"session",owner),map=get(session,"map"),adapter=get(games,"entities");
        Arena arena=(Arena)get(session,"arena");arena.credit(100);
        SummonRoll roll=new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY);
        for(int i=0;i<6;i++)arena.summon(owner.getUniqueId(),roll,(t,r,c)->{
            try{return (UUID)call(adapter,"spawnDefender",map,owner.getUniqueId(),t,r,c);}catch(Exception e){throw new RuntimeException(e);}
        });
        Defender defender=arena.defenders().getFirst();
        if(defender.enhancement()!=5 || defender.damageMultiplier()!=6.5 || arena.defenderCount()!=1)throw new IllegalStateException("Fusion +5 invalid");
        call(adapter,"updateDefenderName",defender);call(games,"fusionEffect",owner,session,defender);
        String name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Bukkit.getEntity(defender.entityId()).customName());
        if(!name.contains("+5"))throw new IllegalStateException("Missing enhancement label");
        arena.select(owner.getUniqueId(),defender.entityId());double before=arena.coins();call(games,"sell",owner);
        if(arena.coins()!=before+360 || arena.defenderCount()!=0)throw new IllegalStateException("Merged sale invalid");
        CampaignRules rules=CampaignRules.standard();Object campaign=get(session,"campaign");
        set(campaign,"elapsed",rules.preparationTicks()+100L*rules.roundTicks()-1);
    }
    private static Object get(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void set(Object object,String name,Object value)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(Method m:object.getClass().getDeclaredMethods())if(m.getName().equals(name) && m.getParameterCount()==args.length) {
            m.setAccessible(true);return m.invoke(object,args);
        }
        throw new NoSuchMethodException(name);
    }
}
