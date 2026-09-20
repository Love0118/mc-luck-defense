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
            try(var service=new BgmService(plugin,games)) {
                var catalog=BgmService.class.getDeclaredField("tracks");catalog.setAccessible(true);
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(((List<?>)catalog.get(service)).isEmpty() && System.nanoTime()<end)Thread.sleep(10);
                assertFalse(((List<?>)catalog.get(service)).isEmpty());
                Track track=new Track("default",new UUID(0,0),"server","default","","https://www.dropbox.com/a?dl=1","a".repeat(40),110.82);
                catalog.set(service,List.of(track));
                var tick=BgmService.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(service);
                for(Player p:List.of(owner,viewer)) verify(p).addResourcePack(eq(track.packId()),eq(track.deliveryUrl()),any(byte[].class),anyString(),eq(false));
                verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,UUID.randomUUID(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);verify(owner,never()).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.packStatus(new PlayerResourcePackStatusEvent(owner,track.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                service.packStatus(new PlayerResourcePackStatusEvent(viewer,track.packId(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
                tick.invoke(service);tick.invoke(service);
                for(Player p:List.of(owner,viewer)) verify(p,times(1)).playSound(any(net.kyori.adventure.sound.Sound.class),any(net.kyori.adventure.sound.Sound.Emitter.class));
                service.toggle(viewer);assertEquals("default",session.bgmTrack);
                verify(viewer).stopSound(track.sound(),SoundCategory.MUSIC);verify(owner,never()).stopSound(anyString(),any(SoundCategory.class));
                tick.invoke(service);verify(viewer,times(1)).addResourcePack(any(),anyString(),any(byte[].class),anyString(),anyBoolean());
                when(games.listeningSession(owner)).thenReturn(null);tick.invoke(service);verify(owner).stopSound(track.sound(),SoundCategory.MUSIC);
            }
        }
    }
}
