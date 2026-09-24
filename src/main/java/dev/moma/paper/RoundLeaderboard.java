package dev.moma.paper;

import dev.moma.core.LeaderboardStore;
import dev.moma.core.RoundRecords;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

final class RoundLeaderboard implements AutoCloseable, Listener {
    private static final String TAG="mud_round_leaderboard";
    private static final int PAGE_SIZE=10;
    private final MomaPlugin plugin;
    private final LeaderboardStore store;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mud-rank-save").factory());
    private final Set<Chunk> chunks=new HashSet<>();
    private final Board current,preseason;
    private final List<Board> boards;
    private final BukkitTask maintenance;
    private boolean closed;
    private int maintenanceTicks;
    private volatile boolean dirty;
    private static final class View {
        TextDisplay display;
        int page;
        long lastClick=Long.MIN_VALUE;
    }
    private final class Board {
        final RoundRecords records;
        final String title;
        final Location location,previousLocation,nextLocation;
        final Map<UUID,View> views=new HashMap<>();
        List<RoundRecords.Entry> ranking;
        TextDisplay previousLabel,nextLabel;
        Interaction previousButton,nextButton;
        Board(LeaderboardStore.Season season,String title,Location location,org.bukkit.util.Vector side)throws java.io.IOException {
            records=store.load(season);ranking=records.top(Integer.MAX_VALUE);this.title=title;this.location=location;
            previousLocation=location.clone().add(side.clone().multiply(2.5)).add(0,.25,0);
            nextLocation=previousLocation.clone().add(0,-.85,0);
        }
        int pages(){return Math.max(1,(ranking.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        void render(View view) {
            view.page=Math.clamp(view.page,0,pages()-1);
            Component text=Ui.text(title+" &7["+(view.page+1)+"/"+pages()+"]");
            int start=view.page*PAGE_SIZE;
            for(int i=0;i<PAGE_SIZE;i++) {
                int rank=start+i;
                text=text.append(Component.newline()).append(Component.text((rank+1)+". ",NamedTextColor.YELLOW));
                text=text.append(rank<ranking.size()?Component.text(ranking.get(rank).name()+"  ·  R"+ranking.get(rank).round(),NamedTextColor.WHITE):Component.text("—",NamedTextColor.GRAY));
            }
            view.display.text(text);
        }
        void maintain() {
            if(previousLabel==null || !previousLabel.isValid()){previousLabel=spawnText(previousLocation,false);previousLabel.text(Ui.text("&e▲ 이전 페이지"));}
            if(nextLabel==null || !nextLabel.isValid()){nextLabel=spawnText(nextLocation,false);nextLabel.text(Ui.text("&e▼ 다음 페이지"));}
            if(previousButton==null || !previousButton.isValid())previousButton=spawnButton(previousLocation);
            if(nextButton==null || !nextButton.isValid())nextButton=spawnButton(nextLocation);
            Set<UUID> present=new HashSet<>();
            for(Player player:Bukkit.getOnlinePlayers())if(player.getWorld().equals(location.getWorld())) {
                present.add(player.getUniqueId());View view=views.computeIfAbsent(player.getUniqueId(),id->new View());
                if(view.display==null || !view.display.isValid()) {
                    view.display=spawnText(location,true);render(view);player.showEntity(plugin,view.display);
                }
            }
            for(UUID id:List.copyOf(views.keySet()))if(!present.contains(id))removeView(id);
        }
        boolean button(Entity entity) {
            UUID id=entity.getUniqueId();
            return previousButton!=null && id.equals(previousButton.getUniqueId())
                    || nextButton!=null && id.equals(nextButton.getUniqueId());
        }
        void turn(Player player,Entity button) {
            if(closed || !player.getWorld().equals(location.getWorld()) || player.getLocation().distanceSquared(button.getLocation())>64)return;
            View view=views.get(player.getUniqueId());if(view==null)return;
            long now=System.nanoTime();
            if(view.lastClick!=Long.MIN_VALUE && now-view.lastClick<200_000_000L)return;
            view.lastClick=now;
            int page=Math.clamp(view.page+(button.getUniqueId().equals(previousButton.getUniqueId())?-1:1),0,pages()-1);
            if(page==view.page)return;
            view.page=page;render(view);Ui.sound(player,Ui.Cue.CLICK);
        }
        void removeView(UUID id){View view=views.remove(id);if(view!=null && view.display!=null)view.display.remove();}
        void removeEntities() {
            for(UUID id:List.copyOf(views.keySet()))removeView(id);
            for(Entity entity:new Entity[]{previousLabel,nextLabel,previousButton,nextButton})if(entity!=null)entity.remove();
        }
    }
    RoundLeaderboard(MomaPlugin plugin,Lobby lobby)throws java.io.IOException {
        this.plugin=plugin;store=new LeaderboardStore(plugin.getDataFolder().toPath());
        Location horizontal=lobby.spawn();horizontal.setPitch(0);
        var forward=horizontal.getDirection();var side=new org.bukkit.util.Vector(-forward.getZ(),0,forward.getX());
        Location location=location(lobby.spawn());
        current=new Board(LeaderboardStore.Season.SEASON_ONE,"&6&l시즌 1 최고 라운드",location,side);
        preseason=new Board(LeaderboardStore.Season.PRESEASON,"&b&l프리시즌 최고 라운드",location.clone().add(side.clone().multiply(7)),side);
        boards=List.of(current,preseason);
        for(Board board:boards)for(Location at:List.of(board.location,board.previousLocation,board.nextLocation))chunks.add(at.getChunk());
        try {
            chunks.forEach(chunk->chunk.addPluginChunkTicket(plugin));
            for(Entity entity:location.getWorld().getEntities())if(entity.getScoreboardTags().contains(TAG))entity.remove();
            maintain();
            Bukkit.getPluginManager().registerEvents(this,plugin);
            maintenance=Bukkit.getScheduler().runTaskTimer(plugin,this::maintain,20,20);
        } catch(RuntimeException error) {
            boards.forEach(Board::removeEntities);chunks.forEach(chunk->chunk.removePluginChunkTicket(plugin));writer.shutdown();throw error;
        }
    }
    static Location location(Location spawn) {
        Location horizontal=spawn.clone();horizontal.setPitch(0);
        return spawn.clone().add(horizontal.getDirection().multiply(5)).add(0,2.8,0);
    }
    void record(Player player,int round) {
        if(!closed && current.records.record(player.getUniqueId(),player.getName(),round)) {
            dirty=true;current.ranking=current.records.top(Integer.MAX_VALUE);
            current.views.values().forEach(current::render);
        }
    }
    private TextDisplay spawnText(Location at,boolean personal) {
        return at.getWorld().spawn(at,TextDisplay.class,entity->{
            entity.addScoreboardTag(TAG);entity.setPersistent(false);entity.setGravity(false);
            entity.setVisibleByDefault(!personal);
            entity.setBillboard(Display.Billboard.CENTER);entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(400);entity.setSeeThrough(false);entity.setShadowed(true);
            entity.setBackgroundColor(Color.fromARGB(180,12,16,24));
        });
    }
    private Interaction spawnButton(Location at) {
        return at.getWorld().spawn(at.clone().subtract(0,.15,0),Interaction.class,entity->{
            entity.addScoreboardTag(TAG);entity.setPersistent(false);entity.setGravity(false);
            entity.setInteractionWidth(1.5f);entity.setInteractionHeight(.65f);entity.setResponsive(true);
        });
    }
    private void maintain() {
        if(closed)return;
        boards.forEach(Board::maintain);
        if(++maintenanceTicks%5==0)flush();
    }
    @EventHandler public void interact(PlayerInteractEntityEvent event) {
        for(Board board:boards)if(board.button(event.getRightClicked())) {
            event.setCancelled(true);
            if(event.getHand()==EquipmentSlot.HAND)board.turn(event.getPlayer(),event.getRightClicked());
            return;
        }
    }
    @EventHandler public void interactAt(PlayerInteractAtEntityEvent event){interact(event);}
    @EventHandler public void attack(EntityDamageByEntityEvent event) {
        for(Board board:boards)if(board.button(event.getEntity())) {
            event.setCancelled(true);
            if(event.getDamager() instanceof Player player)board.turn(player,event.getEntity());
            return;
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event){boards.forEach(board->board.removeView(event.getPlayer().getUniqueId()));}
    @EventHandler public void worldChanged(PlayerChangedWorldEvent event){boards.forEach(board->board.removeView(event.getPlayer().getUniqueId()));}
    private void flush() {
        if(!dirty)return;dirty=false;var snapshot=current.records.snapshot();
        writer.execute(()->{try{store.saveCurrent(snapshot);}catch(Exception e){dirty=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Leaderboard save failed",e);}});
    }
    @Override public void close() {
        if(closed)return;closed=true;maintenance.cancel();HandlerList.unregisterAll(this);
        flush();writer.shutdown();
        try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning("Leaderboard writer still finishing");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        boards.forEach(Board::removeEntities);chunks.forEach(chunk->chunk.removePluginChunkTicket(plugin));
    }
}
