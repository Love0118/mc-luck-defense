package dev.moma.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RebootCommandTest {
    @Test void runtimeSwapRemovesOldCommandAndTheNewCommandCanRebootOnce() {
        var plugin=mock(MomaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reboot-test"));
        var map=new SimpleCommandMap(mock(Server.class),new HashMap<>());
        var scheduler=mock(BukkitScheduler.class);
        var tasks=new ArrayList<Runnable>();
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{tasks.add(call.getArgument(1));return null;});
        var player=mock(Player.class);
        var admin=mock(CommandSender.class);
        when(admin.hasPermission("moma.admin")).thenReturn(true);
        when(admin.getName()).thenReturn("admin");
        var visitor=mock(CommandSender.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCommandMap).thenReturn(map);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            var previous=RebootCommand.register(plugin);
            assertSame(previous,map.getCommand("reboot"));
            assertThrows(IllegalStateException.class,()->RebootCommand.register(plugin));
            previous.remove();
            assertNull(map.getCommand("reboot"));
            var current=RebootCommand.register(plugin);
            assertSame(current,map.getCommand("reboot"));
            assertFalse(map.getKnownCommands().containsValue(previous));
            assertTrue(current.execute(visitor,"reboot",new String[0]));
            assertTrue(current.execute(admin,"reboot",new String[]{"extra"}));
            assertTrue(tasks.isEmpty());
            assertTrue(current.execute(admin,"reboot",new String[0]));
            assertTrue(current.execute(admin,"reboot",new String[0]));
            assertEquals(1,tasks.size());
            bukkit.verify(Bukkit::shutdown,never());
            tasks.getFirst().run();
            bukkit.verify(Bukkit::shutdown,times(1));
            current.remove();
            assertNull(map.getCommand("reboot"));
            verify(player,times(4)).updateCommands();
        }
    }

    @Test void failedSchedulingAllowsRetry() {
        var plugin=mock(MomaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reboot-test"));
        var scheduler=mock(BukkitScheduler.class);
        var map=new SimpleCommandMap(mock(Server.class),new HashMap<>());
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenThrow(new IllegalStateException("scheduler unavailable")).thenReturn(null);
        var admin=mock(CommandSender.class);
        when(admin.hasPermission("moma.admin")).thenReturn(true);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCommandMap).thenReturn(map);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            var command=RebootCommand.register(plugin);
            assertTrue(command.execute(admin,"reboot",new String[0]));
            assertTrue(command.execute(admin,"reboot",new String[0]));
            verify(scheduler,times(2)).runTask(eq(plugin),any(Runnable.class));
            bukkit.verify(Bukkit::shutdown,never());
            command.remove();
        }
    }
}
