package dev.moma.paper;

import dev.moma.bgm.Track;
import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BgmServiceTest {
    @TempDir Path temp;
    private Player player(World world) {
        Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getLocation()).thenReturn(new Location(world,0,65,0));when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());
        when(p.getPersistentDataContainer()).thenReturn(data);return p;
    }
    @Test void packMustLoadAndIndividualMuteNeverChangesSessionTrackOrOtherListener()throws Exception {
        Files.writeString(temp.resolve("bgm.yml"),"refresh-token: ''");
        var plugin=mock(MomaPlugin.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        World world=mock(World.class);Player owner=player(world),viewer=player(world);
        var games=mock(GameService.class);GameSession session=new GameSession(owner,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        when(games.listeningSession(owner)).thenReturn(session);when(games.listeningSession(viewer)).thenReturn(session);
        when(games.watching(viewer)).thenReturn(true);
        var tools=GameService.class.getDeclaredField("tools");tools.setAccessible(true);tools.set(games,mock(SessionTools.class));
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(owner,viewer));
            long[] now={0};
            try(var service=new BgmService(plugin,games,()->now[0])) {
                var catalog=BgmService.class.getDeclaredField("tracks");catalog.setAccessible(true);
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                var initialized=BgmService.class.getDeclaredField("catalogLoaded");initialized.setAccessible(true);
                while(!(boolean)initialized.get(service) && System.nanoTime()<end)Thread.sleep(10);
                assertFalse(((List<?>)catalog.get(service)).isEmpty());
                Track track=new Track("default",new UUID(0,0),"server","default","","https://www.dropbox.com/a?dl=1","a".repeat(40),110.82);
                catalog.set(service,List.of(track));
                var tick=BgmService.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(service);
                for(Player p:List.of(owner,viewer)) verify(p).addResourcePack(eq(track.packId()),eq(track.deliveryUrl()),any(byte[].class),anyString(),eq(false));
                verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,UUID.randomUUID(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,track.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);tick.invoke(service);
                now[0]=1_000_000_000L;
                service.packStatus(new PlayerResourcePackStatusEvent(viewer,track.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);
                verify(viewer,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                now[0]=2_000_000_000L;tick.invoke(service);
                verify(owner,times(2)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                verify(viewer,times(1)).playSound(argThat(sound->sound.source()==net.kyori.adventure.sound.Sound.Source.RECORD && sound.name().asString().equals(track.segmentSound(1))),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.toggle(viewer);assertEquals("default",session.bgmTrack);
                verify(viewer).stopSound(track.segmentSound(1),SoundCategory.RECORDS);
                verify(viewer,never()).removeResourcePack(any());
                tick.invoke(service);verify(viewer,times(1)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                when(games.listeningSession(owner)).thenReturn(null);tick.invoke(service);verify(owner).stopSound(track.segmentSound(1),SoundCategory.RECORDS);
                verify(owner,never()).removeResourcePack(any());
                GameSession next=new GameSession(owner,session.map,CampaignRules.standard());
                when(games.listeningSession(owner)).thenReturn(next);tick.invoke(service);
                verify(owner,times(1)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                verify(owner,times(3)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                // A repaired URL with identical content must reuse the applied hash.
                Track newUrl=new Track("default",new UUID(0,0),"server","default","","https://www.dropbox.com/repaired?dl=1",track.sha1(),110.82);
                catalog.set(service,List.of(newUrl));tick.invoke(service);
                verify(owner,times(1)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                Track revision=new Track("default",new UUID(0,0),"server","default","",newUrl.deliveryUrl(),"b".repeat(40),110.82);
                catalog.set(service,List.of(revision));tick.invoke(service);
                verify(owner).removeResourcePack(track.packId());
                verify(owner).addResourcePack(eq(revision.packId()),anyString(),any(byte[].class),anyString(),eq(false));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,track.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);
                verify(owner,times(3)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,revision.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);
                verify(owner,times(4)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.quit(new org.bukkit.event.player.PlayerQuitEvent(owner,net.kyori.adventure.text.Component.empty()));
                tick.invoke(service);
                verify(owner,times(2)).addResourcePack(eq(revision.packId()),anyString(),any(byte[].class),anyString(),eq(false));
                verify(owner,times(4)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
            }
        }
    }
    @Test void entryPreloadsAllOwnerTracksAndSharedPlaylistPacksEvenForMutedViewer()throws Exception {
        Files.writeString(temp.resolve("bgm.yml"),"refresh-token: ''");
        var plugin=mock(MomaPlugin.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        World world=mock(World.class);Player owner=player(world),viewer=player(world);var games=mock(GameService.class);
        var tools=GameService.class.getDeclaredField("tools");tools.setAccessible(true);tools.set(games,mock(SessionTools.class));
        var session=new GameSession(owner,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        when(games.listeningSession(owner)).thenReturn(session);when(games.listeningSession(viewer)).thenReturn(session);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));bukkit.when(Bukkit::getScheduler).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(owner,viewer));
            try(var service=new BgmService(plugin,games,()->0L)) {
                var initialized=BgmService.class.getDeclaredField("catalogLoaded");initialized.setAccessible(true);
                long deadline=System.nanoTime()+5_000_000_000L;while(!(boolean)initialized.get(service) && System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue((boolean)initialized.get(service));
                List<Track> catalog=new ArrayList<>();
                for(int i=0;i<4;i++)catalog.add(new Track("track"+i,i<3?owner.getUniqueId():UUID.randomUUID(),"uploader","song"+i,"","https://www.dropbox.com/s/"+i+"?dl=1","a".repeat(40),10));
                var field=BgmService.class.getDeclaredField("tracks");field.setAccessible(true);field.set(service,List.copyOf(catalog));
                var lists=BgmService.class.getDeclaredField("playlists");lists.setAccessible(true);
                lists.set(service,Map.of(owner.getUniqueId(),new dev.moma.bgm.BgmPlaylist(List.of("track0","track3"),dev.moma.bgm.BgmTimeline.Mode.MEDLEY,"track0")));
                service.toggle(viewer);
                var tick=BgmService.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(service);tick.invoke(service);
                for(Player listener:List.of(owner,viewer))for(Track track:catalog)
                    verify(listener,times(1)).addResourcePack(eq(track.packId()),eq(track.deliveryUrl()),any(byte[].class),anyString(),eq(false));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,catalog.get(0).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,catalog.get(3).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                for(int i:new int[]{1,2})service.packStatus(new PlayerResourcePackStatusEvent(owner,catalog.get(i).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(owner,times(1)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                verify(viewer,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.toggle(viewer);tick.invoke(service);verify(viewer,times(4)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                for(Track track:catalog)service.packStatus(new PlayerResourcePackStatusEvent(viewer,track.packId(),PlayerResourcePackStatusEvent.Status.DOWNLOADED));
                tick.invoke(service);verify(viewer,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                for(int i=0;i<3;i++)service.packStatus(new PlayerResourcePackStatusEvent(viewer,catalog.get(i).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(viewer,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(viewer,catalog.get(3).packId(),PlayerResourcePackStatusEvent.Status.FAILED_RELOAD));
                service.packStatus(new PlayerResourcePackStatusEvent(viewer,catalog.get(3).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(viewer,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.toggle(viewer);service.toggle(viewer);tick.invoke(service);
                verify(viewer,times(5)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                service.packStatus(new PlayerResourcePackStatusEvent(viewer,catalog.get(3).packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(viewer,times(1)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                lists.set(service,Map.of(owner.getUniqueId(),new dev.moma.bgm.BgmPlaylist(List.of("track0"),dev.moma.bgm.BgmTimeline.Mode.SINGLE,"track0")));
                tick.invoke(service);
                verify(owner,never()).removeResourcePack(any());
                clearInvocations(owner);
                service.packStatus(new PlayerResourcePackStatusEvent(owner,catalog.get(3).packId(),PlayerResourcePackStatusEvent.Status.DISCARDED));
                verify(owner,never()).stopSound(anyString(),any(SoundCategory.class));
                service.stop(owner);tick.invoke(service);
                verify(owner,never()).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                verify(owner).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
            }
        }
    }
}
