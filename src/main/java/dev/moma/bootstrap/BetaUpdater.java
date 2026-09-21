package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Network work stays off the server thread; the existing runtime transaction owns activation. */
final class BetaUpdater implements AutoCloseable {
    private final MomaPlugin host;
    private final RuntimeController runtime;
    private final BetaFeed feed;
    private final Executor executor;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean closed;
    BetaUpdater(MomaPlugin host,RuntimeController runtime) {
        this(host,runtime,new BetaFeed(),Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"mud-beta-update");t.setDaemon(true);return t;}));
    }
    BetaUpdater(MomaPlugin host,RuntimeController runtime,BetaFeed feed,Executor executor) {
        this.host=host;this.runtime=runtime;this.feed=feed;this.executor=executor;
    }
    boolean busy(){return busy.get();}
    void start(CommandSender sender,boolean checkOnly) {
        if(closed)throw new IllegalStateException("업데이트 기능이 종료되었습니다.");
        if(!busy.compareAndSet(false,true)){tell(sender,"이미 베타 업데이트를 진행하고 있습니다.",NamedTextColor.YELLOW);return;}
        String currentHash=runtime.activeHash();
        tell(sender,checkOnly?"베타 배포를 확인하고 있습니다…":"베타 업데이트 확인 → 다운로드 → 검증 → 안전 리로드를 진행합니다…",NamedTextColor.AQUA);
        try {executor.execute(()->download(sender,checkOnly,currentHash));}
        catch(RuntimeException error){busy.set(false);throw error;}
    }
    private void download(CommandSender sender,boolean checkOnly,String currentHash) {
        Path pending=null;
        try {
            BetaFeed.Release release=feed.latest();
            if(!checkOnly && !release.sha256().equals(currentHash))
                pending=feed.fetch(release,host.getDataFolder().toPath().resolve("runtime/downloads"));
            Path downloaded=pending;
            dispatch(()-> {
                try {
                    if(closed)return;
                    if(release.sha256().equals(runtime.activeHash()))tell(sender,"이미 최신 베타입니다. "+release.label(),NamedTextColor.GREEN);
                    else if(checkOnly)tell(sender,"공개 베타: "+release.label()+" · /mud update",NamedTextColor.AQUA);
                    else {
                        tell(sender,"다운로드 검증 완료 · 세션을 확인하고 안전 리로드합니다…",NamedTextColor.AQUA);
                        runtime.installDownloaded(downloaded);
                        tell(sender,"베타 "+release.label()+" 적용 완료 · 게임 세션이 유지되었습니다.",NamedTextColor.GREEN);
                        host.getLogger().info("Beta update applied: "+release.label()+" sha256="+release.sha256());
                    }
                } catch(Exception|LinkageError error){failed(sender,error);}
                finally {cleanup(downloaded);busy.set(false);}
            });
        } catch(Exception error) {
            cleanup(pending);
            try{dispatch(()->{try{if(!closed)failed(sender,error);}finally{busy.set(false);}});}
            catch(RuntimeException scheduling){busy.set(false);host.getLogger().log(Level.WARNING,"Beta update callback unavailable",scheduling);}
        }
    }
    private void dispatch(Runnable action) {
        if(closed){action.run();return;}
        host.getServer().getScheduler().runTask(host,action);
    }
    private void failed(CommandSender sender,Throwable error) {
        host.getLogger().log(Level.WARNING,"Beta update not applied",error);
        tell(sender,"업데이트를 완료하지 못했습니다: "+error.getMessage()+" · 서버를 재시작하지 않았습니다.",NamedTextColor.RED);
    }
    private static void tell(CommandSender sender,String text,NamedTextColor color){sender.sendMessage(Component.text(text,color));}
    private void cleanup(Path path) {
        if(path!=null)try{Files.deleteIfExists(path);}catch(Exception error){host.getLogger().warning("Could not remove update download: "+path);}
    }
    @Override public void close(){closed=true;if(executor instanceof ExecutorService service)service.shutdownNow();}
}
