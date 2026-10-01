package dev.moma.benchmark;

import dev.moma.paper.MomaPlugin;
import dev.moma.bootstrap.GameModule;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class NotificationReloadSmokePlugin extends JavaPlugin {
    private MomaPlugin host;
    private Player owner,viewer,lobby;
    private UUID sessionId;
    private int phase,ticks,phaseAt,roundAt;
    private long loopAt;
    private final List<String> checks=new ArrayList<>();
    @Override public void onEnable() {
        require(Bukkit.getIp().equals("127.0.0.1"),"Localhost only");
        Bukkit.getScheduler().runTaskTimer(this,()->{
            try{tick();}catch(Throwable error){
                getLogger().log(java.util.logging.Level.SEVERE,"NOTIFICATION_RELOAD_FAILED",error);
                Bukkit.shutdown();
            }
        },1,1);
    }
    private Object games()throws Exception{return field(host,"games");}
    private GameModule module()throws Exception{return (GameModule)field(field(host.runtime(),"active"),"module");}
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,games().getClass().getClassLoader());}
    @SuppressWarnings({"unchecked","rawtypes"}) private Object value(String name,String key)throws Exception{return Enum.valueOf((Class)type(name),key);}
    private Object session()throws Exception{return call(games(),"session",owner);}
    private void tick()throws Exception {
        ticks++;
        if(ticks>1600)throw new AssertionError("Fixture timeout");
        if(host==null)host=(MomaPlugin)Bukkit.getPluginManager().getPlugin("MCLuckDefense");
        owner=Bukkit.getPlayerExact("ReloadOwner");viewer=Bukkit.getPlayerExact("ReloadViewer");lobby=Bukkit.getPlayerExact("ReloadLobby");
        if(owner==null || viewer==null || lobby==null)return;
        if(phase==0) {
            require(host.runtime().version().equals("1.0.20"),"Starts with published 1.0.20");
            call(field(games(),"maps"),"create","notifications",6);
            call(games(),"join",owner,"notifications");
            sessionId=(UUID)field(session(),"sessionId");call(games(),"spectate",viewer,sessionId);
            call(field(games(),"tools"),"giveLobby",lobby);
            Object arena=field(session(),"arena");call(arena,"credit",10000L);call(arena,"toggleMerging",owner.getUniqueId());
            for(int i=0;i<40;i++)call(games(),"summon",owner);
            require((int)call(arena,"defenderCount")==36 && (int)call(arena,"reserveCount")==4,"Field and reserve populated");
            Object adapter=field(games(),"entities"),map=field(session(),"map");
            Object enemyType=value("dev.moma.core.EnemyType","ENDER_DRAGON");
            UUID enemyId=(UUID)call(adapter,"spawnEnemy",map,owner.getUniqueId(),enemyType,true);
            Object enemy=type("dev.moma.core.Enemy").getConstructors()[0].newInstance(enemyId,"notifications",enemyType,1e18,2d,.1d,true);
            call(arena,"addEnemy",enemy);
            Object defender=((Collection<?>)call(arena,"activeDefenders")).iterator().next();
            call(games(),"select",owner,call(defender,"entityId"));
            call(session(),"speed",4);
            setting(owner,"other_summon_alerts",false);setting(viewer,"other_summon_alerts",false);
            setting(owner,"own_summon_alerts",false);setting(owner,"round_alerts",false);
            phase=1;return;
        }
        if(phase==1) {
            if((int)call(field(session(),"campaign"),"round")<1)return;
            try{module().checkReloadReady();}catch(IllegalStateException busy){return;}
            Object beforeGames=games(),arena=field(session(),"arena");
            Set<UUID> ids=new HashSet<>();
            for(Object unit:(Collection<?>)call(arena,"units"))ids.add((UUID)call(unit,"entityId"));
            for(Object enemy:(Collection<?>)call(arena,"activeEnemies"))ids.add((UUID)call(enemy,"entityId"));
            var constructor=type("dev.moma.paper.ShopMenu").getDeclaredConstructor(MomaPlugin.class,beforeGames.getClass());constructor.setAccessible(true);
            call(constructor.newInstance(host,beforeGames),"open",owner);
            var oldInventory=owner.getOpenInventory().getTopInventory();
            String fingerprint=module().fingerprint();Location ownerAt=owner.getLocation().clone(),viewerAt=viewer.getLocation().clone();
            require((boolean)call(host.runtime(),"installDownloaded",Path.of("candidate.jar")),"Normal downloaded-update path applied");
            require(host.runtime().version().equals("1.0.22"),"Published 1.0.22 activated");
            require(beforeGames.getClass().getClassLoader()!=games().getClass().getClassLoader(),"Fresh runtime generation");
            require(fingerprint.equals(module().fingerprint()),"Full combat state fingerprint unchanged");
            require(owner.getOpenInventory().getTopInventory()!=oldInventory,"Old GUI closed");
            require(owner.getLocation().equals(ownerAt) && viewer.getLocation().equals(viewerAt),"No owner/viewer teleport");
            require(sessionId.equals(field(session(),"sessionId")),"Same game session");
            require(sessionId.equals(field(call(games(),"listeningSession",viewer),"sessionId")),"Same spectator target");
            for(UUID id:ids)require(Bukkit.getEntity(id)!=null && Bukkit.getEntity(id).isValid(),"Entity survives "+id);
            require(owner.getAllowFlight() && viewer.getAllowFlight() && viewer.getGameMode()==GameMode.ADVENTURE && viewer.isInvisible(),"Player/viewer modes preserved");
            require(!enabled(owner,"other_summon_alerts") && !enabled(viewer,"other_summon_alerts"),"Existing OFF preferences retained");
            require((int)call(field(session(),"arena"),"reserveCount")==4,"Reserve survives");
            checks.add("1.0.20-to-1.0.22-normal-update-with-active-session");checks.add("full-state-fingerprint-and-entities-preserved");
            for(Player p:List.of(owner,viewer,lobby)) {
                require(p.getInventory().getItem(5).getType()==Material.COMPARATOR,"Settings tool replaced in slot 6");
                openSettings(p);require(p.getOpenInventory().getTopInventory().getSize()==27,"Settings GUI opened");
                require(label(p,13).contains(p==lobby?"ON":"OFF"),"Existing preference shown in GUI");
                p.closeInventory();
            }
            checks.add("owner-viewer-lobby-settings-gui");
            mark("OWN_OFF_OTHER_OFF");broadcast();phase=2;phaseAt=ticks;return;
        }
        if(ticks-phaseAt<10)return;
        if(phase==2) {
            openSettings(owner);click(owner,11,true);
            require(enabled(owner,"own_summon_alerts"),"Own alert toggles exactly once");
            require(!enabled(owner,"other_summon_alerts") && !enabled(owner,"round_alerts"),"Other preferences remain independent");
            require(label(owner,11).contains("ON"),"Rendered state refreshed");owner.closeInventory();
            openSettings(viewer);click(viewer,13,true);viewer.closeInventory();
            require(enabled(viewer,"other_summon_alerts"),"Viewer can enable other summons");
            checks.add("gui-toggle-and-duplicate-guard");mark("OWN_ON_OTHER_ON");broadcast();
            phase=3;phaseAt=ticks;return;
        }
        if(phase==3) {
            mark("ROUND_OFF");roundAt=(int)call(field(session(),"campaign"),"round");call(session(),"speed",32);
            phase=4;phaseAt=ticks;return;
        }
        if(phase==4) {
            if((int)call(field(session(),"campaign"),"round")<roundAt+2)return;
            openSettings(owner);click(owner,15,false);owner.closeInventory();
            require(enabled(owner,"round_alerts"),"Round messages reenabled from GUI");
            checks.add("round-progress-while-alert-off");mark("ROUND_ON");roundAt=(int)call(field(session(),"campaign"),"round");
            loopAt=(long)field(games(),"tick");phase=5;phaseAt=ticks;return;
        }
        if(phase==5) {
            if((int)call(field(session(),"campaign"),"round")<=roundAt)return;
            require((long)field(games(),"tick")-loopAt==ticks-phaseAt,"Exactly one game loop after update");
            require((boolean)call(games(),"playing",owner) && (boolean)call(games(),"watching",viewer),"Session and viewer still active");
            checks.add("single-game-loop-and-continued-rounds");
            Files.writeString(Path.of("notification-reload-pass.json"),"{\"base\":\"1.0.20\",\"candidate\":\"1.0.22\",\"activeSession\":true,\"spectator\":true,\"fullStateMatched\":true,\"sessionId\":\""+sessionId+"\",\"checks\":[\""+String.join("\",\"",checks)+"\"]}");
            for(Player p:List.of(owner,viewer,lobby))p.sendMessage("notification-fixture-done");
            getLogger().info("NOTIFICATION_RELOAD_PASSED");
            Bukkit.getScheduler().cancelTasks(this);Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,10);
        }
    }
    private void broadcast()throws Exception {
        Object roll=type("dev.moma.core.SummonRoll").getConstructors()[0].newInstance(value("dev.moma.core.UnitType","WOLF"),value("dev.moma.core.Rarity","TRUE_PRIMORDIAL"));
        Class<?> announcement=type("dev.moma.paper.SummonAnnouncement");
        for(var m:announcement.getDeclaredMethods())if(m.getName().equals("broadcast") && m.getParameterCount()==4) {
            m.setAccessible(true);m.invoke(null,owner,roll,value("dev.moma.core.SummonTier","NORMAL"),(java.util.function.Predicate<Player>)p->p==owner || p==viewer);return;
        }
        throw new NoSuchMethodException("broadcast");
    }
    private void mark(String name){for(Player p:List.of(owner,viewer,lobby))p.sendMessage("notification-phase:"+name);}
    private void openSettings(Player player) {
        player.getInventory().setHeldItemSlot(5);
        var event=new PlayerInteractEvent(player,Action.RIGHT_CLICK_AIR,player.getInventory().getItemInMainHand(),null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);require(event.isCancelled(),"Settings right-click protected");
        require(label(player,11).contains("내 소환 알림"),"New settings GUI on real server");
    }
    private void click(Player player,int slot,boolean twice) {
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);if(twice)Bukkit.getPluginManager().callEvent(event);
        require(event.isCancelled(),"GUI click protected");
    }
    private String label(Player player,int slot){return LegacyComponentSerializer.legacyAmpersand().serialize(player.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().displayName());}
    private static void setting(Player p,String name,boolean enabled){p.getPersistentDataContainer().set(new NamespacedKey("momadefense",name),PersistentDataType.BYTE,(byte)(enabled?1:0));}
    private static boolean enabled(Player p,String name){return p.getPersistentDataContainer().getOrDefault(new NamespacedKey("momadefense",name),PersistentDataType.BYTE,(byte)1)!=0;}
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(var m:object.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
            boolean matches=true;for(int i=0;i<args.length;i++)if(args[i]!=null && !m.getParameterTypes()[i].isPrimitive() && !m.getParameterTypes()[i].isInstance(args[i]))matches=false;
            if(matches){m.setAccessible(true);return m.invoke(object,args);}
        }throw new NoSuchMethodException(name);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
