package dev.moma.paper;

import dev.moma.bgm.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
    private volatile boolean catalogLoaded;
    private volatile Map<UUID,BgmPlaylist> playlists=Map.of();
    private final Map<UUID,BgmTimeline> timelines=new HashMap<>();
    private final java.util.function.LongSupplier clock;
    private volatile boolean closed;
    private final AtomicBoolean checking=new AtomicBoolean();
    private final Set<UUID> uploading=ConcurrentHashMap.newKeySet();
    private record Prompt(UUID session,boolean auth,long expires) {}
    private final Map<UUID,Prompt> prompts=new ConcurrentHashMap<>();
    private final Map<UUID,Playback> playback=new HashMap<>();
    private final Map<UUID,Integer> clicks=new HashMap<>();
    private long nextHealthCheck;
    private static final class Playback {
        UUID session;String lastCue,currentSound;
        final Map<UUID,PackState> packs=new LinkedHashMap<>();
    }
    private static final class PackState {
        final Track track;final long requested;boolean loaded,failed;
        PackState(Track track,long requested){this.track=track;this.requested=requested;}
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner,session;final boolean library;final int page;final List<String> ids;
        Inventory inventory;boolean consumed;
        Holder(UUID owner,UUID session,boolean library,int page,List<String> ids){this.owner=owner;this.session=session;this.library=library;this.page=page;this.ids=ids;}
        @Override public Inventory getInventory(){return inventory;}
    }
    BgmService(MomaPlugin plugin,GameService games)throws Exception {
        this(plugin,games,System::nanoTime);
    }
    BgmService(MomaPlugin plugin,GameService games,java.util.function.LongSupplier clock)throws Exception {
        this.clock=clock;
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
                store=new BgmStore(data.resolve("bgm.db"));tracks=store.list();playlists=store.playlists();
                if(tracks.stream().noneMatch(t->t.id().equals("default"))) {
                    store.save(new Track("default",SYSTEM,"서버","기본 BGM","","","",config.getDouble("default-duration-seconds",110.82)));tracks=store.list();
                }
                catalogLoaded=true;
                if(dropbox.connected()) repairAll();
            } catch(Exception e){plugin.getLogger().warning("BGM initialization: "+e.getClass().getSimpleName());}
        });
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getPluginManager().registerEvents(new BgmChatInput(id->{
            Prompt prompt=prompts.remove(id);
            return prompt==null?null:input->receiveInput(id,prompt,input);
        }),plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    private Track find(String id){return tracks.stream().filter(t->t.id().equals(id)).findFirst().orElse(null);}
    private BgmPlaylist playlist(GameSession session) {
        return playlists.getOrDefault(session.arena.owner(),new BgmPlaylist(tracks.stream()
                .filter(t->t.uploader().equals(session.arena.owner())).limit(3).map(Track::id).toList(),BgmTimeline.Mode.SINGLE,session.bgmTrack));
    }
    private List<Track> selectedTracks(GameSession session) {
        List<Track> selected=playlist(session).tracks().stream().map(this::find).filter(Objects::nonNull).toList();
        return selected.isEmpty()?Optional.ofNullable(find("default")).stream().toList():selected;
    }
    private List<Track> preloadTracks(GameSession session) {
        LinkedHashMap<String,Track> preload=new LinkedHashMap<>();
        tracks.stream().filter(t->t.uploader().equals(session.arena.owner())).limit(3).forEach(t->preload.put(t.id(),t));
        selectedTracks(session).forEach(t->preload.put(t.id(),t));
        if(playlist(session).selected().equals("default") && find("default")!=null)preload.put("default",find("default"));
        return preload.values().stream().filter(Track::synchronizedReady).toList();
    }
    private void savePlaylist(Player player,GameSession session,BgmPlaylist next) {
        UUID owner=player.getUniqueId();
        if(!owner.equals(session.arena.owner()) || games.session(player)!=session)return;
        if(!submit(()->{
            try {store.savePlaylist(owner,next);}
            catch(Exception error){message(owner,"&c재생 목록 저장에 실패했습니다.");}
        })) {player.sendMessage(Ui.text("&e처리 대기열이 가득 찼습니다."));return;}
        Map<UUID,BgmPlaylist> updated=new HashMap<>(playlists);updated.put(owner,next);playlists=Map.copyOf(updated);
        session.bgmTrack=next.selected();
        Ui.sound(player,Ui.Cue.CLICK);open(player,false,0);tick();
    }
    private boolean muted(Player player){return player.getPersistentDataContainer().getOrDefault(MUTED,PersistentDataType.BYTE,(byte)0)!=0;}
    private void message(UUID player,String text){main(()->{Player p=Bukkit.getPlayer(player);if(p!=null)p.sendMessage(Ui.text(text));});}
    private void main(Runnable task){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{if(!closed)task.run();});}
    private boolean submit(Runnable task){try{worker.execute(task);return true;}catch(RejectedExecutionException e){return false;}}
    void use(Player player) {
        if(games.playing(player)) open(player,false,0); else if(games.watching(player)) toggle(player);
    }
    void toggle(Player player) {
        boolean value=!muted(player);player.getPersistentDataContainer().set(MUTED,PersistentDataType.BYTE,(byte)(value?1:0));
        if(value)silence(player,playback.get(player.getUniqueId())); games.tools.updateBgm(player,games.watching(player),!value);
        if(!value) {
            Playback state=playback.get(player.getUniqueId());
            if(state!=null)for(UUID id:List.copyOf(state.packs.keySet()))if(state.packs.get(id).failed){state.packs.remove(id);player.removeResourcePack(id);}
        }
        player.sendActionBar(Ui.text(value?"&7BGM OFF":"&aBGM ON"));Ui.sound(player,Ui.Cue.CLICK);
    }
    void open(Player player,boolean library,int page) {
        GameSession session=games.session(player);if(session==null || session.arena.ended())return;
        if(!catalogLoaded){player.sendMessage(Ui.text("&eBGM 목록을 불러오는 중입니다."));return;}
        BgmPlaylist selected=playlist(session);
        List<Track> choices=library?tracks.stream().filter(t->!t.id().equals("default")).toList():selected.tracks().stream().map(this::find).filter(Objects::nonNull).toList();
        int pages=Math.max(1,(choices.size()+8)/9);page=Math.clamp(page,0,pages-1);
        if(library)choices=choices.subList(page*9,Math.min(choices.size(),page*9+9));
        Holder holder=new Holder(player.getUniqueId(),session.sessionId,library,page,choices.stream().map(Track::id).toList());
        holder.inventory=Bukkit.createInventory(holder,18,Ui.text(library?"&6업로드된 곡 · "+(page+1):"&6BGM 관리"));
        for(int i=0;i<choices.size();i++) {
            Track t=choices.get(i);holder.inventory.setItem(i,Ui.item(Material.MUSIC_DISC_CAT,"&f"+t.title(),"&7업로드: "+t.uploaderName(),
                    library ? (selected.tracks().contains(t.id())?"&e클릭: 재생 목록에서 제거":"&a클릭: 재생 목록에 추가") : "&a좌클릭: 선택 · 우클릭: 목록에서 제거",
                    t.synchronizedReady()?"&7재생 가능":"&e동기화 팩 준비 중"));
        }
        if(library) {
            holder.inventory.setItem(9,Ui.item(Material.ARROW,"&e이전 페이지"));holder.inventory.setItem(13,Ui.item(Material.BARRIER,"&e내 곡으로"));
            if((page+1)*9<tracks.stream().filter(t->!t.id().equals("default")).count())holder.inventory.setItem(17,Ui.item(Material.ARROW,"&e다음 페이지"));
        } else {
            holder.inventory.setItem(4,Ui.item(Material.MUSIC_DISC_13,"&b기본 BGM"));
            holder.inventory.setItem(5,Ui.item(Material.REPEATER,"&b재생 모드 · &e"+(selected.mode()==BgmTimeline.Mode.SINGLE?"단일곡":"메들리"),"&7클릭: 단일곡 반복 / 순서대로 반복"));
            holder.inventory.setItem(8,Ui.item(muted(player)?Material.GRAY_DYE:Material.LIME_DYE,muted(player)?"&7BGM OFF":"&aBGM ON"));
            holder.inventory.setItem(13,Ui.item(Material.HOPPER,"&a노래 업로드", "&7YouTube 링크로 등록 · 최대 3곡"));
            holder.inventory.setItem(17,Ui.item(Material.BOOK,"&e업로드된 곡 모두 보기"));
        }
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || (event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT) || h.consumed)return;
        GameSession s=games.session(p);if(s==null || s.arena.ended() || !s.sessionId.equals(h.session))return;
        int slot=event.getRawSlot();if(slot<0 || slot>=18)return;
        if(clicks.getOrDefault(p.getUniqueId(),-1)==Bukkit.getCurrentTick())return;
        clicks.put(p.getUniqueId(),Bukkit.getCurrentTick());h.consumed=true;
        if(slot<h.ids.size()) {
            if(h.library || event.getClick()==ClickType.RIGHT) {
                try {savePlaylist(p,s,playlist(s).toggle(h.ids.get(slot)));}
                catch(IllegalArgumentException error){p.sendMessage(Ui.text("&e"+error.getMessage()));h.consumed=false;}
            } else select(p,s,h.ids.get(slot));
        }
        else if(event.getClick()!=ClickType.LEFT){h.consumed=false;return;}
        else if(!h.library && slot==4)select(p,s,"default");
        else if(!h.library && slot==5) {
            BgmPlaylist current=playlist(s);
            savePlaylist(p,s,new BgmPlaylist(current.tracks(),current.mode()==BgmTimeline.Mode.SINGLE?BgmTimeline.Mode.MEDLEY:BgmTimeline.Mode.SINGLE,current.selected()));
        }
        else if(!h.library && slot==8){toggle(p);open(p,false,0);}
        else if(!h.library && slot==13)promptUpload(p,s);
        else if(!h.library && slot==17)open(p,true,0);
        else if(h.library && slot==9)open(p,true,h.page-1);
        else if(h.library && slot==17)open(p,true,h.page+1);
        else if(h.library && slot==13)open(p,false,0);
        else h.consumed=false;
    }
    private void select(Player player,GameSession session,String id) {
        Track t=find(id);if(t==null || !t.synchronizedReady()) {player.sendMessage(Ui.text("&e곡 배포가 준비되지 않았습니다."));open(player,false,0);return;}
        BgmPlaylist current=playlist(session);
        savePlaylist(player,session,new BgmPlaylist(current.tracks(),BgmTimeline.Mode.SINGLE,id));
    }
    private void promptUpload(Player player,GameSession session) {
        if(!dropbox.connected()){player.sendMessage(Ui.text("&e관리자가 Dropbox 연결을 완료해야 합니다."));player.closeInventory();return;}
        if(tracks.stream().filter(t->t.uploader().equals(player.getUniqueId())).count()>=3 || uploading.contains(player.getUniqueId())) {
            player.sendMessage(Ui.text("&e최대 3곡까지 등록할 수 있으며 업로드는 한 번에 하나씩 가능합니다."));player.closeInventory();return;
        }
        prompts.put(player.getUniqueId(),new Prompt(session.sessionId,false,System.currentTimeMillis()+120000));player.closeInventory();
        player.sendMessage(Ui.text("&a채팅에 YouTube 링크를 입력하세요. &7취소: 취소 · 제한: 5분, 25MB"));
    }
    void auth(Player player) {
        if(!player.hasPermission("moma.admin"))throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        prompts.put(player.getUniqueId(),new Prompt(null,true,System.currentTimeMillis()+600000));
        player.sendMessage(Ui.text("&a[Dropbox 계정 연결]").clickEvent(ClickEvent.openUrl(dropbox.authorizationUrl(UUID.randomUUID().toString()))));
        player.sendMessage(Ui.text("&7인증 화면의 코드를 채팅으로 입력하세요. 코드는 다른 플레이어에게 전송되지 않습니다. 취소: 취소"));
    }
    private void receiveInput(UUID id,Prompt prompt,String input) {
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
        } catch(BgmMedia.RejectedAudio e) {message(owner,"&e"+e.getMessage());}
        catch(Exception e) {message(owner,"&cBGM 등록 실패: 영상 접근·외부 도구·Dropbox 연결을 확인하세요.");plugin.getLogger().warning("BGM upload failed: "+e.getClass().getSimpleName());}
        finally {cleanup(temp);uploading.remove(owner);}
    }
    private Track publish(Track track,Path audio,Path temp)throws Exception {
        Path pack=media.synchronizedPack(track.id(),audio,track.seconds(),temp);String sha=BgmMedia.sha1(pack);String url=dropbox.publish(track.id(),sha,pack);
        return new Track(track.id(),track.uploader(),track.uploaderName(),track.title(),track.youtubeUrl(),url,sha,track.seconds());
    }
    private void repairAll() {
        if(!dropbox.connected() || store==null)return;
        for(Track track:tracks) {
            Path temp=null;
            try {
                if(track.synchronizedReady() && dropbox.healthy(track.deliveryUrl(),track.sha1()))continue;
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
        long nowNanos=clock.getAsLong();
        prompts.entrySet().removeIf(e->e.getValue().expires<now);
        if(now>=nextHealthCheck && checking.compareAndSet(false,true)) {
            nextHealthCheck=now+Math.max(5,config.getInt("health-check-minutes",30))*60000L;
            if(!submit(()->{try{repairAll();}finally{checking.set(false);}}))checking.set(false);
        }
        Set<UUID> liveSessions=new HashSet<>();
        for(Player player:Bukkit.getOnlinePlayers()) {
            GameSession session=games.listeningSession(player);
            if(session==null || session.arena.ended()){stop(player);continue;}
            liveSessions.add(session.sessionId);
            if(!catalogLoaded)continue;
            BgmPlaylist selected=playlist(session);
            List<Track> sequence=new ArrayList<>(selectedTracks(session));
            if(selected.mode()==BgmTimeline.Mode.SINGLE && selected.selected().equals("default") && find("default")!=null)sequence=List.of(find("default"));
            BgmTimeline timeline=timelines.computeIfAbsent(session.sessionId,id->new BgmTimeline());
            timeline.configure(sequence,selected.mode(),selected.selected());
            Playback state=playback.get(player.getUniqueId());
            if(state==null || !session.sessionId.equals(state.session)) {
                stop(player);state=new Playback();state.session=session.sessionId;
                playback.put(player.getUniqueId(),state);
            }
            List<Track> preload=preloadTracks(session);Set<UUID> desired=new HashSet<>();
            for(Track track:preload)desired.add(track.packId());
            for(UUID id:List.copyOf(state.packs.keySet()))if(!desired.contains(id)) {state.packs.remove(id);player.removeResourcePack(id);silence(player,state);}
            for(Track track:preload) {
                PackState pack=state.packs.get(track.packId());
                if(pack==null || !pack.track.deliveryUrl().equals(track.deliveryUrl())) {
                    state.packs.put(track.packId(),new PackState(track,now));
                    player.addResourcePack(track.packId(),track.deliveryUrl(),HexFormat.of().parseHex(track.sha1()),"세션 BGM 재생 목록을 내려받습니다.",false);
                } else if(!pack.loaded && !pack.failed && now-pack.requested>120000) {
                    pack.failed=true;player.sendMessage(Ui.text("&eBGM 리소스팩 적용 시간이 초과되었습니다. BGM을 껐다 켜서 다시 시도하세요."));
                }
            }
            boolean ownerReady=player.getUniqueId().equals(session.arena.owner()) && !sequence.isEmpty()
                    && sequence.stream().allMatch(t->t.synchronizedReady() && loaded(player,t.packId()));
            if(ownerReady)timeline.start(nowNanos);
            BgmTimeline.Cue cue=timeline.at(nowNanos);
            if(muted(player) || cue==null || !loaded(player,cue.track().packId())) {silence(player,state);continue;}
            String cueId=timeline.revision()+":"+cue.identity();
            if(!cueId.equals(state.lastCue)) {
                silence(player,state);
                // Do not replay a past section when a pack finishes loading or the server stalls.
                if(cue.lateMillis()>150)continue;
                player.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key(cue.sound()),net.kyori.adventure.sound.Sound.Source.RECORD,1f,1f),net.kyori.adventure.sound.Sound.Emitter.self());
                state.lastCue=cueId;state.currentSound=cue.sound();
            }
        }
        timelines.keySet().retainAll(liveSessions);
    }
    private boolean loaded(Player player,UUID pack) {
        Playback state=playback.get(player.getUniqueId());PackState value=state==null?null:state.packs.get(pack);
        return value!=null && value.loaded;
    }
    private void silence(Player player,Playback state) {
        if(state==null)return;
        if(state.currentSound!=null)player.stopSound(state.currentSound,SoundCategory.RECORDS);
        state.currentSound=null;state.lastCue=null;
    }
    void stop(Player player) {
        Playback state=playback.remove(player.getUniqueId());if(state==null)return;
        silence(player,state);state.packs.keySet().forEach(player::removeResourcePack);
    }
    @EventHandler public void packStatus(PlayerResourcePackStatusEvent event) {
        Playback state=playback.get(event.getPlayer().getUniqueId());PackState pack=state==null?null:state.packs.get(event.getID());if(pack==null)return;
        switch(event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {pack.loaded=true;pack.failed=false;}
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                pack.failed=true;pack.loaded=false;silence(event.getPlayer(),state);event.getPlayer().sendMessage(Ui.text("&eBGM 리소스팩을 적용하지 못했습니다. BGM을 껐다 켜서 다시 시도하세요."));
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
