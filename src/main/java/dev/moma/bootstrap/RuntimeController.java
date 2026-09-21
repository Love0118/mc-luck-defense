package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Main-thread transaction: validate and prepare first, replace bindings, commit the selected artifact last. */
public final class RuntimeController implements CommandExecutor,TabCompleter,AutoCloseable {
    private record Loaded(RuntimeArtifact artifact,RuntimeLoader loader,GameModule module) {}
    private final MomaPlugin host;
    private final Path installed,updates,cache,pointer;
    private final RuntimeArtifact hostArtifact;
    private Loaded active;
    private RuntimeArtifact previous;
    private boolean changing;

    public RuntimeController(MomaPlugin host,Path installed,Path updates)throws IOException {
        this.host=host;this.installed=installed;this.updates=updates;
        cache=host.getDataFolder().toPath().resolve("runtime/cache");pointer=cache.getParent().resolve("active.properties");
        Files.createDirectories(cache);hostArtifact=RuntimeArtifact.inspect(installed);
    }
    public void start()throws Exception {
        RuntimeArtifact selected=cache(installed);
        if(Files.isRegularFile(pointer)) {
            try {
                Properties p=new Properties();try(var input=Files.newInputStream(pointer)){p.load(input);}
                RuntimeArtifact saved=fromHash(p.getProperty("active"));
                if(saved.hostHash().equals(hostArtifact.hostHash()) && hostArtifact.sha256().equals(p.getProperty("host"))) {
                    selected=saved;
                    if(p.containsKey("previous"))previous=fromHash(p.getProperty("previous"));
                }
            } catch(Exception error){host.getLogger().warning("Saved runtime unavailable; using installed JAR: "+error.getMessage());}
        }
        active=load(selected);
        try {active.module.prepare(host,null);active.module.activate(true);host.runtimeChanged(active.module.diagnosticState());}
        catch(Exception|LinkageError error){active.module.shutdown();active.loader.close();active=null;throw error;}
    }
    private RuntimeArtifact fromHash(String hash)throws IOException {
        if(hash==null || !hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid runtime pointer");
        RuntimeArtifact artifact=RuntimeArtifact.inspect(cache.resolve(hash+".jar"));
        if(!artifact.sha256().equals(hash))throw new IOException("Runtime checksum mismatch");
        return artifact;
    }
    private RuntimeArtifact cache(Path source)throws IOException {
        RuntimeArtifact artifact=RuntimeArtifact.inspect(source);
        Path target=cache.resolve(artifact.sha256()+".jar");
        if(!Files.exists(target)) {
            Path pending=Files.createTempFile(cache,"copy-",".tmp");
            try {
                Files.copy(source,pending,StandardCopyOption.REPLACE_EXISTING);
                if(!RuntimeArtifact.hash(pending).equals(artifact.sha256()))throw new IOException("JAR가 복사 중 변경되었습니다.");
                Files.move(pending,target,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(pending);}
        }
        return fromHash(artifact.sha256());
    }
    private Loaded load(RuntimeArtifact artifact)throws Exception {
        RuntimeLoader loader=new RuntimeLoader(artifact.path(),MomaPlugin.class.getClassLoader());
        try {
            GameModule module=(GameModule)loader.loadClass("dev.moma.paper.GameRuntime").getConstructor().newInstance();
            return new Loaded(artifact,loader,module);
        } catch(Exception|LinkageError error){loader.close();throw error;}
    }
    private Path candidate() {
        Path staged=updates.resolve(installed.getFileName());
        return Files.isRegularFile(staged)?staged:active.artifact.path();
    }
    private void compatible(RuntimeArtifact artifact) {
        if(!artifact.hostHash().equals(hostArtifact.hostHash()))
            throw new IllegalArgumentException("로더·의존성·플러그인 설정이 바뀐 업데이트는 정상 재시작이 필요합니다.");
        if(active.module.hasSessions() && (!artifact.schema().equals(active.artifact.schema()) || !artifact.balanceHash().equals(active.artifact.balanceHash())))
            throw new IllegalArgumentException("진행 중인 게임과 상태 형식 또는 밸런스가 다릅니다. 세션 종료 후 적용하세요.");
    }
    public String check()throws Exception {
        requireMainThread();RuntimeArtifact artifact=cache(candidate());compatible(artifact);active.module.checkReloadReady();
        byte[] state=active.module.hasSessions() || artifact.schema().equals(active.artifact.schema())?active.module.snapshot():null;
        Loaded next=load(artifact);
        try {
            next.module.prepare(host,state);
            if(state!=null && !next.module.fingerprint().equals(active.module.fingerprint()))throw new IllegalStateException("세션 복원 검증이 일치하지 않습니다.");
        } finally {next.loader.close();}
        return artifact.version();
    }
    public void reload()throws Exception {requireMainThread();replace(cache(candidate()));}
    public void rollback()throws Exception {
        requireMainThread();
        if(previous==null)throw new IllegalArgumentException("되돌릴 이전 버전이 없습니다.");
        replace(previous);
    }
    private void replace(RuntimeArtifact artifact)throws Exception {
        requireMainThread();if(changing)throw new IllegalStateException("이미 업데이트 중입니다.");changing=true;
        Loaded next=null,old=active;boolean suspended=false;
        try {
            compatible(artifact);old.module.checkReloadReady();
            byte[] state=old.module.hasSessions() || artifact.schema().equals(old.artifact.schema())?old.module.snapshot():null;
            next=load(artifact);next.module.prepare(host,state);
            if(state!=null && !next.module.fingerprint().equals(old.module.fingerprint()))throw new IllegalStateException("세션 복원 검증이 일치하지 않습니다.");
            suspended=true;old.module.suspend();
            next.module.activate(false);
            RuntimeArtifact nextPrevious=artifact.sha256().equals(old.artifact.sha256())?previous:old.artifact;
            persist(artifact,nextPrevious);
            active=next;previous=nextPrevious;next=null;
            host.runtimeChanged(active.module.diagnosticState());
            consumeStaged(artifact);
            try{old.loader.close();}catch(IOException close){host.getLogger().warning("Old runtime JAR handle could not close: "+close.getMessage());}
        } catch(Exception|LinkageError error) {
            if(suspended) {
                if(next!=null)try{next.module.discard();}catch(Exception|LinkageError cleanup){error.addSuppressed(cleanup);}
                try {old.module.activate(false);host.runtimeChanged(old.module.diagnosticState());}
                catch(Exception|LinkageError rollback){error.addSuppressed(rollback);host.getLogger().severe("Runtime rollback failed; sessions retained but runtime needs attention.");}
                consumeStaged(artifact);
            }
            throw error;
        } finally {
            try{if(next!=null)next.loader.close();}finally{changing=false;}
        }
    }
    private void persist(RuntimeArtifact current,RuntimeArtifact old)throws IOException {
        Properties properties=new Properties();properties.setProperty("active",current.sha256());properties.setProperty("host",hostArtifact.sha256());if(old!=null)properties.setProperty("previous",old.sha256());
        Path temp=Files.createTempFile(pointer.getParent(),"active-",".tmp");
        try {
            try(var out=Files.newOutputStream(temp)){properties.store(out,"MC Luck Defense runtime selection");}
            try{Files.move(temp,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,pointer,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
    private void consumeStaged(RuntimeArtifact artifact) {
        Path staged=updates.resolve(installed.getFileName()),held=null;
        try {
            if(!Files.isRegularFile(staged) || !RuntimeArtifact.hash(staged).equals(artifact.sha256()))return;
            held=Files.createTempFile(cache.getParent(),"staged-",".jar");
            Files.move(staged,held,StandardCopyOption.REPLACE_EXISTING);
            if(RuntimeArtifact.hash(held).equals(artifact.sha256()))Files.delete(held); // Identical bytes remain in the verified cache.
            else if(!Files.exists(staged))Files.move(held,staged);
            else host.getLogger().warning("Staged JAR changed during update; retained at "+held);
        } catch(IOException error){host.getLogger().warning("Could not retire staged runtime; inspect "+staged+" and "+held+": "+error.getMessage());}
    }
    private static void requireMainThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Runtime updates require the server thread");}
    public String version(){return active.artifact.version();}
    public String status(){return "로더 "+hostArtifact.version()+" · 게임 "+version()+(previous==null?"":" · 이전 "+previous.version());}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        String action=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        if(!Set.of("reload","rollback","version").contains(action))return active.module.onCommand(sender,command,label,args);
        if(!sender.hasPermission("moma.admin")){sender.sendMessage(Component.text("관리자만 사용할 수 있습니다.",NamedTextColor.RED));return true;}
        try {
            if(action.equals("version"))sender.sendMessage(Component.text(status(),NamedTextColor.AQUA));
            else if(action.equals("rollback")){rollback();sender.sendMessage(Component.text("세션을 유지하고 게임 "+version()+" 버전으로 되돌렸습니다.",NamedTextColor.GREEN));}
            else if(args.length==2 && args[1].equalsIgnoreCase("check"))sender.sendMessage(Component.text("게임 "+check()+" · 세션 유지 업데이트 가능",NamedTextColor.GREEN));
            else if(args.length==1){reload();sender.sendMessage(Component.text("게임 "+version()+" 적용 완료 · 진행 중인 세션을 유지했습니다.",NamedTextColor.GREEN));}
            else sender.sendMessage(Component.text("/mud reload [check] | rollback | version",NamedTextColor.YELLOW));
        } catch(Exception|LinkageError error) {
            host.getLogger().log(java.util.logging.Level.WARNING,"Runtime update rejected or rolled back",error);
            sender.sendMessage(Component.text("업데이트하지 못했습니다: "+error.getMessage(),NamedTextColor.RED));
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==2 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("moma.admin"))return List.of("check");
        var choices=new ArrayList<>(Optional.ofNullable(active.module.onTabComplete(sender,command,alias,args)).orElse(List.of()));
        if(args.length==1 && sender.hasPermission("moma.admin"))for(String extra:List.of("reload","rollback","version"))if(extra.startsWith(args[0].toLowerCase(Locale.ROOT)))choices.add(extra);
        return choices;
    }
    @Override public void close()throws Exception {
        if(active!=null){active.module.shutdown();active.loader.close();active=null;}
    }
}
