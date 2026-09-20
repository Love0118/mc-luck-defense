package dev.moma.paper;

import dev.moma.bgm.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Bukkit state belongs to the main thread; the bounded worker owns SQLite and all media/network IO. */
final class BgmService implements Listener, AutoCloseable {
    private static final UUID SYSTEM=new UUID(0,0);
    private static final NamespacedKey MUTED=new NamespacedKey("momadefense","bgm_muted");
    private final MomaPlugin plugin;
    private final GameService games;
    private final Path data,work;
    private final YamlConfiguration config;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"mud-bgm");t.setDaemon(true);return t;});
    private final DropboxBgm dropbox;
    private final BgmMedia media;
    private BgmStore store;
    private volatile List<Track> tracks=List.of();
    private volatile boolean closed;
    private final AtomicBoolean checking=new AtomicBoolean();
    private final Set<UUID> uploading=ConcurrentHashMap.newKeySet();
    private record Prompt(UUID session,boolean auth,long expires) {}
    private final Map<UUID,Prompt> prompts=new ConcurrentHashMap<>();
    private final Map<UUID,Playback> playback=new HashMap<>();
    private final Map<UUID,Integer> clicks=new HashMap<>();
    private long nextHealthCheck;
    private static final class Playback {
        UUID session,pack;String track,hash,url;boolean loaded,failed;long nextPlay,requested;
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner,session;final boolean library;final int page;final List<String> ids;
        Inventory inventory;boolean consumed;
        Holder(UUID owner,UUID session,boolean library,int page,List<String> ids){this.owner=owner;this.session=session;this.library=library;this.page=page;this.ids=ids;}
        @Override public Inventory getInventory(){return inventory;}
    }
    BgmService(MomaPlugin plugin,GameService games)throws Exception {
        this.plugin=plugin;this.games=games;data=plugin.getDataFolder().toPath();work=data.resolve("bgm-work");
        Files.createDirectories(work);
        if(!Files.exists(data.resolve("bgm.yml")))plugin.saveResource("bgm.yml",false);
        config=YamlConfiguration.loadConfiguration(data.resolve("bgm.yml").toFile());
        dropbox=new DropboxBgm(config.getString("refresh-token",""));
        media=new BgmMedia(config.getString("yt-dlp","yt-dlp"),config.getString("ffmpeg","ffmpeg"));
        worker.execute(()->{
            try {
                try(var leftovers=Files.list(work)) {
                    for(Path path:leftovers.filter(Files::isDirectory).toList()) BgmMedia.cleanup(path);
                }
                store=new BgmStore(data.resolve("bgm.db"));tracks=store.list();
                if(tracks.stream().noneMatch(t->t.id().equals("default"))) {
                    store.save(new Track("default",SYSTEM,"서버","기본 BGM","","","",config.getDouble("default-duration-seconds",110.82)));tracks=store.list();
                }
                if(dropbox.connected()) repairAll();
            } catch(Exception e){plugin.getLogger().warning("BGM initialization: "+e.getClass().getSimpleName());}
        });
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }
    private Track find(String id){return tracks.stream().filter(t->t.id().equals(id)).findFirst().orElse(null);}
    private boolean muted(Player player){return player.getPersistentDataContainer().getOrDefault(MUTED,PersistentDataType.BYTE,(byte)0)!=0;}
    private void message(UUID player,String text){main(()->{Player p=Bukkit.getPlayer(player);if(p!=null)p.sendMessage(Ui.text(text));});}
    private void main(Runnable task){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{if(!closed)task.run();});}
    private boolean submit(Runnable task){try{worker.execute(task);return true;}catch(RejectedExecutionException e){return false;}}
    void use(Player player) {
        if(games.playing(player)) open(player,false,0); else if(games.watching(player)) toggle(player);
    }
    void toggle(Player player) {
        boolean value=!muted(player);player.getPersistentDataContainer().set(MUTED,PersistentDataType.BYTE,(byte)(value?1:0));
        if(value)stop(player); games.tools.updateBgm(player,games.watching(player),!value);
        player.sendActionBar(Ui.text(value?"&7BGM OFF":"&aBGM ON"));Ui.sound(player,Ui.Cue.CLICK);
    }
    void open(Player player,boolean library,int page) {
        GameSession session=games.session(player);if(session==null || session.arena.ended())return;
        List<Track> choices=library?tracks.stream().filter(t->!t.id().equals("default")).toList():tracks.stream().filter(t->t.uploader().equals(player.getUniqueId())).limit(3).toList();
        int pages=Math.max(1,(choices.size()+8)/9);page=Math.clamp(page,0,pages-1);
        if(library)choices=choices.subList(page*9,Math.min(choices.size(),page*9+9));
        Holder holder=new Holder(player.getUniqueId(),session.sessionId,library,page,choices.stream().map(Track::id).toList());
        holder.inventory=Bukkit.createInventory(holder,18,Ui.text(library?"&6업로드된 곡 · "+(page+1):"&6BGM 관리"));
        for(int i=0;i<choices.size();i++) {
            Track t=choices.get(i);holder.inventory.setItem(i,Ui.item(Material.MUSIC_DISC_CAT,"&f"+t.title(),"&7업로드: "+t.uploaderName(),t.ready()?"&a클릭하여 재생":"&e배포 준비 중"));
        }
        if(library) {
            holder.inventory.setItem(9,Ui.item(Material.ARROW,"&e이전 페이지"));holder.inventory.setItem(13,Ui.item(Material.BARRIER,"&e내 곡으로"));
            if((page+1)*9<tracks.stream().filter(t->!t.id().equals("default")).count())holder.inventory.setItem(17,Ui.item(Material.ARROW,"&e다음 페이지"));
        } else {
            holder.inventory.setItem(4,Ui.item(Material.MUSIC_DISC_13,"&b기본 BGM"));
            holder.inventory.setItem(8,Ui.item(muted(player)?Material.GRAY_DYE:Material.LIME_DYE,muted(player)?"&7BGM OFF":"&aBGM ON"));
            holder.inventory.setItem(13,Ui.item(Material.HOPPER,"&a노래 업로드", "&7YouTube 링크로 등록 · 최대 3곡"));
            holder.inventory.setItem(17,Ui.item(Material.BOOK,"&e업로드된 곡 모두 보기"));
        }
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || event.getClick()!=ClickType.LEFT || h.consumed)return;
        GameSession s=games.session(p);if(s==null || s.arena.ended() || !s.sessionId.equals(h.session))return;
        int slot=event.getRawSlot();if(slot<0 || slot>=18)return;
        if(clicks.getOrDefault(p.getUniqueId(),-1)==Bukkit.getCurrentTick())return;
        clicks.put(p.getUniqueId(),Bukkit.getCurrentTick());h.consumed=true;
        if(slot<h.ids.size())select(p,s,h.ids.get(slot));
        else if(!h.library && slot==4)select(p,s,"default");
        else if(!h.library && slot==8){toggle(p);open(p,false,0);}
        else if(!h.library && slot==13)promptUpload(p,s);
        else if(!h.library && slot==17)open(p,true,0);
        else if(h.library && slot==9)open(p,true,h.page-1);
        else if(h.library && slot==17)open(p,true,h.page+1);
        else if(h.library && slot==13)open(p,false,0);
        else h.consumed=false;
    }
    private void select(Player player,GameSession session,String id) {
        Track t=find(id);if(t==null || !t.ready()) {player.sendMessage(Ui.text("&e곡 배포가 준비되지 않았습니다."));open(player,false,0);return;}
        session.bgmTrack=id;player.closeInventory();Ui.sound(player,Ui.Cue.CLICK);tick();
    }
    private void promptUpload(Player player,GameSession session) {
        if(!dropbox.connected()){player.sendMessage(Ui.text("&e관리자가 Dropbox 연결을 완료해야 합니다."));player.closeInventory();return;}
        if(tracks.stream().filter(t->t.uploader().equals(player.getUniqueId())).count()>=3 || uploading.contains(player.getUniqueId())) {
            player.sendMessage(Ui.text("&e최대 3곡까지 등록할 수 있으며 업로드는 한 번에 하나씩 가능합니다."));player.closeInventory();return;
        }
        prompts.put(player.getUniqueId(),new Prompt(session.sessionId,false,System.currentTimeMillis()+120000));player.closeInventory();
        player.sendMessage(Ui.text("&a채팅에 YouTube 링크를 입력하세요. &7취소: 취소 · 제한: 10분, 25MB"));
    }
    void auth(Player player) {
        if(!player.hasPermission("moma.admin"))throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        prompts.put(player.getUniqueId(),new Prompt(null,true,System.currentTimeMillis()+600000));
        player.sendMessage(Ui.text("&a[Dropbox 계정 연결]").clickEvent(ClickEvent.openUrl(dropbox.authorizationUrl(UUID.randomUUID().toString()))));
        player.sendMessage(Ui.text("&7인증 화면의 코드를 채팅으로 입력하세요. 코드는 다른 플레이어에게 전송되지 않습니다. 취소: 취소"));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void chat(AsyncChatEvent event) {
        UUID id=event.getPlayer().getUniqueId();Prompt prompt=prompts.remove(id);if(prompt==null)return;
        event.setCancelled(true);
        String input=PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        main(()->{
            Player p=Bukkit.getPlayer(id);if(p==null)return;
            if(input.equals("취소") || input.equalsIgnoreCase("cancel")){p.sendMessage(Ui.text("&7취소했습니다."));return;}
            if(prompt.expires<System.currentTimeMillis()){p.sendMessage(Ui.text("&e입력 시간이 만료되었습니다."));return;}
            if(prompt.auth) {
                if(!p.hasPermission("moma.admin"))return;
                if(!submit(()->{try {
                    String token=dropbox.authorize(input);
                    var credentials=YamlConfiguration.loadConfiguration(data.resolve("bgm.yml").toFile());
                    credentials.set("refresh-token",token);credentials.save(data.resolve("bgm.yml").toFile());
                    message(id,"&aDropbox 연결 완료. 기본 BGM을 배포합니다.");repairAll();
                }catch(Exception e){message(id,"&cDropbox 인증/배포 실패. 코드를 다시 발급하거나 도구 설정을 확인하세요.");}}))p.sendMessage(Ui.text("&e업로드 대기열이 가득 찼습니다."));
                return;
            }
            GameSession s=games.session(p);if(s==null || !s.sessionId.equals(prompt.session))return;
            final String url;try{url=BgmMedia.youtube(input);}catch(IllegalArgumentException e){p.sendMessage(Ui.text("&c"+e.getMessage()));return;}
            if(!uploading.add(id))return;String name=p.getName();
            if(!submit(()->upload(id,name,url))) {uploading.remove(id);p.sendMessage(Ui.text("&e업로드 대기열이 가득 찼습니다."));}
            else p.sendMessage(Ui.text("&e다운로드·변환·배포를 시작했습니다."));
        });
    }
    private void upload(UUID owner,String name,String url) {
        Path temp=null;
        try {
            if(store==null)throw new IllegalStateException();
            if(store.list().stream().filter(t->t.uploader().equals(owner)).count()>=3)throw new IllegalStateException();
            temp=Files.createTempDirectory(work,"upload-");BgmMedia.Audio audio=media.download(url,temp);
            Track draft=new Track(UUID.randomUUID().toString().replace("-",""),owner,name,audio.title(),url,"","",audio.seconds());
            Track ready=publish(draft,audio.file(),temp);store.save(ready);tracks=store.list();
            message(owner,"&aBGM 등록 완료: &f"+audio.title());
        } catch(Exception e) {message(owner,"&cBGM 등록 실패: 영상 접근·외부 도구·Dropbox 연결을 확인하세요.");plugin.getLogger().warning("BGM upload failed: "+e.getClass().getSimpleName());}
        finally {cleanup(temp);uploading.remove(owner);}
    }
    private Track publish(Track track,Path audio,Path temp)throws Exception {
        Path pack=BgmMedia.pack(track.id(),audio,temp);String sha=BgmMedia.sha1(pack);String url=dropbox.publish(track.id(),sha,pack);
        return new Track(track.id(),track.uploader(),track.uploaderName(),track.title(),track.youtubeUrl(),url,sha,track.seconds());
    }
    private void repairAll() {
        if(!dropbox.connected() || store==null)return;
        for(Track track:tracks) {
            Path temp=null;
            try {
                if(track.ready() && dropbox.healthy(track.deliveryUrl(),track.sha1()))continue;
                temp=Files.createTempDirectory(work,"repair-");BgmMedia.Audio audio;
                if(track.id().equals("default")) {
                    Path source=temp.resolve("default.ogg");
                    try(var in=plugin.getResource("bgm/default.ogg")){if(in==null)throw new IllegalStateException("Missing default BGM");Files.copy(in,source);}
                    audio=new BgmMedia.Audio(source,track.title(),track.seconds());
                } else audio=media.download(track.youtubeUrl(),temp);
                Track draft=new Track(track.id(),track.uploader(),track.uploaderName(),audio.title(),track.youtubeUrl(),"","",audio.seconds());
                store.save(publish(draft,audio.file(),temp));tracks=store.list();
            } catch(Exception e){plugin.getLogger().warning("BGM repair pending for "+track.id()+": "+e.getClass().getSimpleName());}
            finally{cleanup(temp);}
        }
    }
    private void cleanup(Path temp){if(temp!=null)try{BgmMedia.cleanup(temp);}catch(Exception e){plugin.getLogger().warning("BGM temporary cleanup failed");}}
    private void tick() {
        long now=System.currentTimeMillis();
        prompts.entrySet().removeIf(e->e.getValue().expires<now);
        if(now>=nextHealthCheck && checking.compareAndSet(false,true)) {
            nextHealthCheck=now+Math.max(5,config.getInt("health-check-minutes",30))*60000L;
            if(!submit(()->{try{repairAll();}finally{checking.set(false);}}))checking.set(false);
        }
        for(Player player:Bukkit.getOnlinePlayers()) {
            GameSession session=games.listeningSession(player);
            if(session==null || session.arena.ended() || muted(player)){stop(player);continue;}
            Track track=find(session.bgmTrack);if(track==null || !track.ready()){stop(player);continue;}
            Playback state=playback.get(player.getUniqueId());
            if(state==null || !session.sessionId.equals(state.session) || !track.id().equals(state.track) || !track.sha1().equals(state.hash) || !track.deliveryUrl().equals(state.url)) {
                stop(player);state=new Playback();state.session=session.sessionId;state.track=track.id();state.hash=track.sha1();state.url=track.deliveryUrl();state.pack=track.packId();state.requested=now;
                playback.put(player.getUniqueId(),state);
                player.addResourcePack(state.pack,track.deliveryUrl(),HexFormat.of().parseHex(track.sha1()),"BGM을 재생하려면 리소스팩을 적용하세요.",false);
            }
            if(!state.loaded && !state.failed && now-state.requested>120000) {state.failed=true;player.sendMessage(Ui.text("&eBGM 리소스팩 적용 시간이 초과되었습니다. BGM을 껐다 켜서 다시 시도하세요."));}
            if(state.loaded && now>=state.nextPlay) {
                player.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key(track.sound()),net.kyori.adventure.sound.Sound.Source.MUSIC,.6f,1f),net.kyori.adventure.sound.Sound.Emitter.self());
                state.nextPlay=now+(long)(track.seconds()*1000)+500;
            }
        }
    }
    void stop(Player player) {
        Playback state=playback.remove(player.getUniqueId());if(state==null)return;
        player.stopSound("mud_bgm:track_"+state.track,SoundCategory.MUSIC);player.removeResourcePack(state.pack);
    }
    @EventHandler public void packStatus(PlayerResourcePackStatusEvent event) {
        Playback state=playback.get(event.getPlayer().getUniqueId());if(state==null || !state.pack.equals(event.getID()))return;
        switch(event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {state.loaded=true;state.failed=false;}
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                state.failed=true;state.loaded=false;event.getPlayer().sendMessage(Ui.text("&eBGM 리소스팩을 적용하지 못했습니다. BGM을 껐다 켜서 다시 시도하세요."));
                if(event.getStatus()!=PlayerResourcePackStatusEvent.Status.DECLINED)nextHealthCheck=Math.min(nextHealthCheck,System.currentTimeMillis()+60000);
            }
            default -> {}
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event){prompts.remove(event.getPlayer().getUniqueId());clicks.remove(event.getPlayer().getUniqueId());stop(event.getPlayer());}
    @EventHandler public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler public void closeMenu(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof Holder h)h.consumed=true;}
    @Override public void close() {
        closed=true;for(Player player:Bukkit.getOnlinePlayers())stop(player);
        prompts.clear();worker.shutdownNow();
        try {
            if(worker.awaitTermination(2,TimeUnit.SECONDS)) {if(store!=null)store.close();return;}
        } catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){plugin.getLogger().warning("BGM database close failed");}
        Thread.ofPlatform().daemon().name("mud-bgm-close").start(()->{try{if(worker.awaitTermination(15,TimeUnit.SECONDS) && store!=null)store.close();}catch(Exception ignored){}});
    }
}
