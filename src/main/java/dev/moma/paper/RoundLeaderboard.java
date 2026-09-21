package dev.moma.paper;

import dev.moma.core.RoundRecords;
import java.nio.file.Path;
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
    private final RoundRecords records;
    private final Path file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mud-rank-save").factory());
    private final Location location,previousLocation,nextLocation;
    private final Set<Chunk> chunks=new HashSet<>();
    private final Map<UUID,View> views=new HashMap<>();
    private final BukkitTask maintenance;
    private List<RoundRecords.Entry> ranking;
    private TextDisplay previousLabel,nextLabel;
    private Interaction previousButton,nextButton;
    private boolean closed;
    private int maintenanceTicks;
    private volatile boolean dirty;
    private static final class View {
        TextDisplay display;
        int page;
        long lastClick=Long.MIN_VALUE;
    }
    RoundLeaderboard(MomaPlugin plugin,Lobby lobby)throws java.io.IOException {
        this.plugin=plugin;file=plugin.getDataFolder().toPath().resolve("round-records.properties");records=RoundRecords.load(file);
        ranking=records.top(Integer.MAX_VALUE);
        location=location(lobby.spawn());
        Location horizontal=lobby.spawn();horizontal.setPitch(0);
        var forward=horizontal.getDirection();
        var right=new org.bukkit.util.Vector(-forward.getZ(),0,forward.getX()).multiply(2.5);
        previousLocation=location.clone().add(right).add(0,.25,0);
        nextLocation=previousLocation.clone().add(0,-.85,0);
        for(Location at:List.of(location,previousLocation,nextLocation))chunks.add(at.getChunk());
        chunks.forEach(chunk->chunk.addPluginChunkTicket(plugin));
        try {
            for(Entity entity:location.getWorld().getEntities())if(entity.getScoreboardTags().contains(TAG))entity.remove();
            maintain();
            Bukkit.getPluginManager().registerEvents(this,plugin);
            maintenance=Bukkit.getScheduler().runTaskTimer(plugin,this::maintain,20,20);
        } catch(RuntimeException error) {
            removeEntities();chunks.forEach(chunk->chunk.removePluginChunkTicket(plugin));writer.shutdown();throw error;
        }
    }
    static Location location(Location spawn) {
        Location horizontal=spawn.clone();horizontal.setPitch(0);
        return spawn.clone().add(horizontal.getDirection().multiply(5)).add(0,2.8,0);
    }
    void record(Player player,int round) {
        if(records.record(player.getUniqueId(),player.getName(),round)) {
            dirty=true;ranking=records.top(Integer.MAX_VALUE);
            views.values().forEach(this::render);
        }
    }
    private int pages(){return Math.max(1,(ranking.size()+PAGE_SIZE-1)/PAGE_SIZE);}
    private void render(View view) {
        view.page=Math.clamp(view.page,0,pages()-1);
        Component text=Ui.text("&6&l최고 라운드 &7["+(view.page+1)+"/"+pages()+"]");
        int start=view.page*PAGE_SIZE;
        for(int i=0;i<PAGE_SIZE;i++) {
            int rank=start+i;
            text=text.append(Component.newline()).append(Component.text((rank+1)+". ",NamedTextColor.YELLOW));
            text=text.append(rank<ranking.size()?Component.text(ranking.get(rank).name()+"  ·  R"+ranking.get(rank).round(),NamedTextColor.WHITE):Component.text("—",NamedTextColor.GRAY));
        }
        view.display.text(text);
    }
    private TextDisplay spawnText(Location at,boolean personal) {
        return at.getWorld().spawn(at,TextDisplay.class,entity->{
            entity.addScoreboardTag(TAG);entity.setPersistent(false);entity.setGravity(false);
            // Set before spawn packets are created: only showEntity's recipient can track this board.
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
        if(previousLabel==null || !previousLabel.isValid()){previousLabel=spawnText(previousLocation,false);previousLabel.text(Ui.text("&e▲ 이전 페이지"));}
        if(nextLabel==null || !nextLabel.isValid()){nextLabel=spawnText(nextLocation,false);nextLabel.text(Ui.text("&e▼ 다음 페이지"));}
        if(previousButton==null || !previousButton.isValid())previousButton=spawnButton(previousLocation);
        if(nextButton==null || !nextButton.isValid())nextButton=spawnButton(nextLocation);
        Set<UUID> present=new HashSet<>();
        for(Player player:Bukkit.getOnlinePlayers())if(player.getWorld().equals(location.getWorld())) {
            present.add(player.getUniqueId());View view=views.computeIfAbsent(player.getUniqueId(),id->new View());
            if(view.display==null || !view.display.isValid()) {
                view.display=spawnText(location,true);render(view);
                player.showEntity(plugin,view.display);
            }
        }
        for(UUID id:List.copyOf(views.keySet()))if(!present.contains(id))removeView(id);
        if(++maintenanceTicks%5==0)flush();
    }
    private boolean button(Entity entity) {
        UUID id=entity.getUniqueId();
        return previousButton!=null && id.equals(previousButton.getUniqueId())
                || nextButton!=null && id.equals(nextButton.getUniqueId());
    }
    private void turn(Player player,Entity button) {
        if(closed || !player.getWorld().equals(location.getWorld()) || player.getLocation().distanceSquared(button.getLocation())>64)return;
        View view=views.get(player.getUniqueId());if(view==null)return;
        long now=System.nanoTime();
        if(view.lastClick!=Long.MIN_VALUE && now-view.lastClick<200_000_000L)return;
        view.lastClick=now;
        int page=Math.clamp(view.page+(button.getUniqueId().equals(previousButton.getUniqueId())?-1:1),0,pages()-1);
        if(page==view.page)return;
        view.page=page;render(view);Ui.sound(player,Ui.Cue.CLICK);
    }
    // Lobby protections cancel interaction too; page controls intentionally still handle these events.
    @EventHandler public void interact(PlayerInteractEntityEvent event) {
        if(!button(event.getRightClicked()))return;
        event.setCancelled(true);
        if(event.getHand()==EquipmentSlot.HAND)turn(event.getPlayer(),event.getRightClicked());
    }
    @EventHandler public void interactAt(PlayerInteractAtEntityEvent event){interact(event);}
    @EventHandler public void attack(EntityDamageByEntityEvent event) {
        if(!button(event.getEntity()))return;
        event.setCancelled(true);
        if(event.getDamager() instanceof Player player)turn(player,event.getEntity());
    }
    @EventHandler public void quit(PlayerQuitEvent event){removeView(event.getPlayer().getUniqueId());}
    @EventHandler public void worldChanged(PlayerChangedWorldEvent event){removeView(event.getPlayer().getUniqueId());}
    private void removeView(UUID id) {View view=views.remove(id);if(view!=null && view.display!=null)view.display.remove();}
    private void removeEntities() {
        for(UUID id:List.copyOf(views.keySet()))removeView(id);
        for(Entity entity:new Entity[]{previousLabel,nextLabel,previousButton,nextButton})if(entity!=null)entity.remove();
    }
    private void flush() {
        if(!dirty)return;dirty=false;var snapshot=records.snapshot();
        writer.execute(()->{try{RoundRecords.save(file,snapshot);}catch(Exception e){dirty=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Leaderboard save failed",e);}});
    }
    @Override public void close() {
        if(closed)return;closed=true;maintenance.cancel();HandlerList.unregisterAll(this);
        flush();writer.shutdown();
        try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning("Leaderboard writer still finishing");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        removeEntities();chunks.forEach(chunk->chunk.removePluginChunkTicket(plugin));
    }
}
