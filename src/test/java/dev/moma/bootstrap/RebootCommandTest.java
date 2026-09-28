package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import java.util.ArrayList;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RebootCommandTest {
    @Test void schedulesOneCleanShutdownAfterAdminCommandReturns() {
        var plugin=mock(MomaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reboot-test"));
        var scheduler=mock(BukkitScheduler.class);
        var tasks=new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{tasks.add(call.getArgument(1));return null;});
        var admin=mock(CommandSender.class);
        when(admin.hasPermission("moma.admin")).thenReturn(true);
        when(admin.getName()).thenReturn("admin");
        var player=mock(CommandSender.class);
        var executor=new RebootCommand(plugin);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertTrue(executor.onCommand(player,mock(Command.class),"reboot",new String[0]));
            assertTrue(executor.onCommand(admin,mock(Command.class),"reboot",new String[]{"extra"}));
            assertTrue(tasks.isEmpty());
            assertTrue(executor.onCommand(admin,mock(Command.class),"reboot",new String[0]));
            assertTrue(executor.onCommand(admin,mock(Command.class),"reboot",new String[0]));
            assertEquals(1,tasks.size());
            bukkit.verify(Bukkit::shutdown,never());
            tasks.getFirst().run();
            bukkit.verify(Bukkit::shutdown,times(1));
        }
    }

    @Test void schedulingFailureDoesNotLatchTheRebootCommand() {
        var plugin=mock(MomaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reboot-test"));
        var scheduler=mock(BukkitScheduler.class);
        var admin=mock(CommandSender.class);
        when(admin.hasPermission("moma.admin")).thenReturn(true);
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenThrow(new IllegalStateException("scheduler unavailable")).thenReturn(null);
        var executor=new RebootCommand(plugin);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertTrue(executor.onCommand(admin,mock(Command.class),"reboot",new String[0]));
            assertTrue(executor.onCommand(admin,mock(Command.class),"reboot",new String[0]));
            verify(scheduler,times(2)).runTask(eq(plugin),any(Runnable.class));
            bukkit.verify(Bukkit::shutdown,never());
        }
    }
}
