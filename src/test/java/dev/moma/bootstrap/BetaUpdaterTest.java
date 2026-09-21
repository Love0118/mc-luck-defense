package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BetaUpdaterTest {
    @TempDir Path dir;
    private MomaPlugin host;private RuntimeController runtime;private BetaFeed feed;
    private final Queue<Runnable> background=new ArrayDeque<>(),main=new ArrayDeque<>();
    private Path download;
    @BeforeEach void setup()throws Exception {
        host=mock(MomaPlugin.class);runtime=mock(RuntimeController.class);feed=mock(BetaFeed.class);
        when(host.getDataFolder()).thenReturn(dir.toFile());when(host.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        Server server=mock(Server.class);BukkitScheduler scheduler=mock(BukkitScheduler.class);
        when(host.getServer()).thenReturn(server);when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(host),any(Runnable.class))).thenAnswer(c->{main.add(c.getArgument(1));return null;});
        when(runtime.activeHash()).thenReturn("old");
        when(feed.latest()).thenReturn(new BetaFeed.Release("0.17.0","a".repeat(40),"b".repeat(64),100));
        download=Files.writeString(dir.resolve("download.part"),"fixture");when(feed.fetch(any(),any())).thenReturn(download);
    }
    @Test void downloadsOffThreadAndAppliesOnceOnMainThreadWithDuplicateGuard()throws Exception {
        try(var updater=new BetaUpdater(host,runtime,feed,background::add)) {
            var sender=mock(CommandSender.class);updater.start(sender,false);updater.start(sender,false);
            assertTrue(updater.busy());assertEquals(1,background.size());verifyNoInteractions(feed);
            background.remove().run();verify(runtime,never()).installDownloaded(any());assertTrue(Files.exists(download));
            main.remove().run();verify(runtime,times(1)).installDownloaded(download);assertFalse(updater.busy());assertFalse(Files.exists(download));
        }
    }
    @Test void checkAndAlreadyCurrentNeverDownloadOrReload()throws Exception {
        try(var updater=new BetaUpdater(host,runtime,feed,background::add)) {
            updater.start(mock(CommandSender.class),true);background.remove().run();main.remove().run();
            when(runtime.activeHash()).thenReturn("b".repeat(64));
            updater.start(mock(CommandSender.class),false);background.remove().run();main.remove().run();
            verify(feed,never()).fetch(any(),any());verify(runtime,never()).installDownloaded(any());assertFalse(updater.busy());
        }
    }
    @Test void failedActivationAndNetworkErrorsReleaseGuardWithoutRestart()throws Exception {
        when(runtime.installDownloaded(download)).thenThrow(new IllegalArgumentException("incompatible"));
        try(var updater=new BetaUpdater(host,runtime,feed,background::add)) {
            updater.start(mock(CommandSender.class),false);background.remove().run();main.remove().run();
            assertFalse(updater.busy());assertFalse(Files.exists(download));
            when(feed.latest()).thenThrow(new java.io.IOException("offline"));
            updater.start(mock(CommandSender.class),false);background.remove().run();main.remove().run();
            assertFalse(updater.busy());verify(runtime,times(1)).installDownloaded(any());
        }
    }
    @Test void shutdownBeforeCallbackPreventsActivationAndRemovesDownload()throws Exception {
        var updater=new BetaUpdater(host,runtime,feed,background::add);
        updater.start(mock(CommandSender.class),false);background.remove().run();updater.close();main.remove().run();
        verify(runtime,never()).installDownloaded(any());assertFalse(Files.exists(download));assertFalse(updater.busy());
    }
}
