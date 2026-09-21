package dev.moma.benchmark;

import dev.moma.paper.MomaPlugin;
import dev.moma.bootstrap.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses the public host boundary; resolves internal game classes from each active generation. */
public final class ReloadSmokePlugin extends JavaPlugin {
    private MomaPlugin host;
    private Player owner,viewer;
    private int ticks,phase;
    private UUID sessionId,defenderId,enemyId;
    private long tickAt;
    private int phaseAt;
    private final List<String> checks=new ArrayList<>();
    @Override public void onEnable() {
        if(!Bukkit.getIp().equals("127.0.0.1"))throw new IllegalStateException("Localhost only");
        Bukkit.getScheduler().runTaskTimer(this,()->{try{tick();}catch(Throwable error){
            getLogger().log(java.util.logging.Level.SEVERE,"RELOAD_SMOKE_FAILED",error);Bukkit.shutdown();
        }},1,1);
    }
    private Object games()throws Exception{return field(host,"games");}
    private GameModule module()throws Exception{return (GameModule)field(field(host.runtime(),"active"),"module");}
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,games().getClass().getClassLoader());}
    @SuppressWarnings({"unchecked","rawtypes"}) private Object value(String name,String constant)throws Exception{return Enum.valueOf((Class)type(name),constant);}
    private void stage(String name)throws Exception {
        Path update=Bukkit.getUpdateFolderFile().toPath();Files.createDirectories(update);
        Files.copy(Path.of("candidates",name+".jar"),update.resolve("MCLuckDefense.jar"),StandardCopyOption.REPLACE_EXISTING);
    }
    private void tick()throws Exception {
        ticks++;
        if(host==null)host=(MomaPlugin)Bukkit.getPluginManager().getPlugin("MCLuckDefense");
        owner=Bukkit.getPlayerExact("ReloadOwner");viewer=Bukkit.getPlayerExact("ReloadViewer");
        if(owner==null || viewer==null)return;
        if(phase==0) {
            Object maps=field(games(),"maps");call(maps,"create","reload",6);
            var data=owner.getPersistentDataContainer();
            data.set(new NamespacedKey("mcluckdefense","achievement_round"),PersistentDataType.LONG,500L);
            data.set(new NamespacedKey("mcluckdefense","trait_loadout"),PersistentDataType.STRING,"round_250,enhancement_500");
            data.set(new NamespacedKey("mcluckdefense","achievement_enhancement"),PersistentDataType.LONG,500L);
            call(games(),"join",owner,"reload");
            Object session=call(games(),"session",owner),arena=field(session,"arena"),map=field(session,"map");
            sessionId=(UUID)field(session,"sessionId");call(games(),"spectate",viewer,sessionId);
            call(arena,"credit",10000L);call(session,"speed",8);
            Object adapter=field(games(),"entities");
            Object enemyType=value("dev.moma.core.EnemyType","ENDER_DRAGON");
            enemyId=(UUID)call(adapter,"spawnEnemy",map,owner.getUniqueId(),enemyType,true);
            Object enemy=type("dev.moma.core.Enemy").getConstructors()[0].newInstance(enemyId,"reload",enemyType,1e18,2d,.1d,true);
            call(arena,"addEnemy",enemy);
            for(int i=0;i<6;i++)call(games(),"summon",owner);
            Object defender=((Collection<?>)call(arena,"activeDefenders")).iterator().next();
            defenderId=(UUID)call(defender,"entityId");call(arena,"select",owner.getUniqueId(),defenderId);
            call(adapter,"selectGlow",owner,defenderId);
            set(session,"autoPlacement",true);set(session,"bulkBuying",true);set(session,"bulkPurchases",7);
            phase=1;phaseAt=ticks;return;
        }
        if(ticks-phaseAt<30)return;
        try{module().checkReloadReady();}catch(IllegalStateException busy){if(ticks-phaseAt>300)throw busy;return;}
        if(phase==1) {
            phase=2;phaseAt=ticks;
            verifyTransaction("compatible",false);
            require(host.runtime().version().equals("0.16.1-test"),"Candidate version");
            require(call(module(),"validationMarker").equals("candidate-one"),"New code executed");
            checks.add("compatible-new-code");return;
        }
        if(phase==2) {
            phase=3;phaseAt=ticks;verifyTransaction("incompatible",true);checks.add("incompatible-rejected");
            stage("compatible");Path config=host.getDataFolder().toPath().resolve("bgm.yml");String original=Files.readString(config);
            Object same=games();String before=module().fingerprint();boolean rejected=false;
            try {
                Files.writeString(config,"limits:\n  uploads-per-player: -1\n");
                try{host.runtime().reload();}catch(Exception expected){rejected=true;}
            } finally {Files.writeString(config,original);}
            require(rejected && same==games() && before.equals(module().fingerprint()),"Invalid config rejected before suspend");
            checks.add("invalid-config-rejected");return;
        }
        if(phase==3) {
            phase=4;phaseAt=ticks;verifyTransaction("activation-failure",true);
            require(call(module(),"validationMarker").equals("candidate-one"),"Old code resumed after failure");
            checks.add("activation-rollback");return;
        }
        if(phase==4) {
            phase=5;phaseAt=ticks;
            String before=module().fingerprint();host.runtime().rollback();
            require(before.equals(module().fingerprint()),"Explicit rollback preserves current state");
            require(host.runtime().version().equals("0.16.0"),"Explicit rollback version");
            validatePlayers();checks.add("explicit-rollback");return;
        }
        if(phase==5) {
            phase=6;phaseAt=ticks;stage("base");
            String before=module().fingerprint();ClassLoader old=games().getClass().getClassLoader();
            host.runtime().reload();
            require(before.equals(module().fingerprint()) && old!=games().getClass().getClassLoader(),"Same-version reload");
            validatePlayers();checks.add("same-version-reload");return;
        }
        if(phase==6){phase=7;phaseAt=ticks;tickAt=(long)field(games(),"tick");return;}
        if(phase==7) {
            require((long)field(games(),"tick")-tickAt==ticks-phaseAt,"Exactly one game loop remains");
            validatePlayers();checks.add("single-game-loop");
            Files.writeString(Path.of("reload-pass.json"),"{\"checks\":[\""+String.join("\",\"",checks)+"\"],\"sessionId\":\""+sessionId
                    +"\",\"sameEntities\":true,\"playerAndSpectatorPreserved\":true,\"stateFingerprintMatched\":true}");
            owner.sendMessage("reload-fixture-done");viewer.sendMessage("reload-fixture-done");
            Bukkit.getScheduler().cancelTasks(this);Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,10);
        }
    }
    private void verifyTransaction(String candidate,boolean rejected)throws Exception {
        var constructor=type("dev.moma.paper.ShopMenu").getDeclaredConstructor(MomaPlugin.class,games().getClass());constructor.setAccessible(true);
        call(constructor.newInstance(host,games()),"open",owner);
        var inventory=owner.getOpenInventory().getTopInventory();
        stage(candidate);Object previous=games();String before=module().fingerprint();Location at=owner.getLocation().clone();
        boolean failed=false;
        try{host.runtime().reload();}catch(Exception expected){if(!rejected)throw expected;failed=true;}
        require(failed==rejected,"Expected rejection");
        require(before.equals(module().fingerprint()),"All game fields, timers and RNG positions match");
        if(rejected)require(games()==previous,"Old runtime retained");
        else require(games().getClass().getClassLoader()!=previous.getClass().getClassLoader(),"New classloader");
        if(!rejected || candidate.equals("activation-failure"))require(owner.getOpenInventory().getTopInventory()!=inventory,"Old runtime GUI closed");
        require(owner.getLocation().equals(at),"No player teleport");validatePlayers();
    }
    private void validatePlayers()throws Exception {
        Object session=call(games(),"session",owner);require(sessionId.equals(field(session,"sessionId")),"Session identity");
        require(sessionId.equals(field(call(games(),"listeningSession",viewer),"sessionId")),"Spectator target");
        require(Bukkit.getEntity(defenderId)!=null && Bukkit.getEntity(enemyId)!=null,"Entities survive");
        require(owner.getAllowFlight() && viewer.getAllowFlight(),"Flight preserved");
        require(viewer.getGameMode()==GameMode.ADVENTURE && viewer.isInvisible(),"Spectator appearance restored");
        require(owner.getPersistentDataContainer().getOrDefault(new NamespacedKey("mcluckdefense","achievement_session"),PersistentDataType.LONG,0L)==1L,"Reload must not count as another session");
    }
    private static Object field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String name,Object value)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object call(Object object,String name,Object...args)throws Exception {
        for(var method:object.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            boolean matches=true;Class<?>[] parameters=method.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null && !parameters[i].isPrimitive() && !parameters[i].isInstance(args[i]))matches=false;
            if(!matches)continue;method.setAccessible(true);return method.invoke(object,args);
        }throw new NoSuchMethodException(name);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
