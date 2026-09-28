package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class RebootCommand implements CommandExecutor {
    private final MomaPlugin plugin;
    private boolean pending;

    public RebootCommand(MomaPlugin plugin) { this.plugin=plugin; }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("moma.admin")) {
            sender.sendMessage(Component.text("관리자만 사용할 수 있습니다.",NamedTextColor.RED));
            return true;
        }
        if(args.length!=0) {
            sender.sendMessage(Component.text("/reboot",NamedTextColor.YELLOW));
            return true;
        }
        if(pending) {
            sender.sendMessage(Component.text("서버 재시작이 이미 예약되었습니다.",NamedTextColor.YELLOW));
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin,Bukkit::shutdown);
        } catch(IllegalStateException error) {
            sender.sendMessage(Component.text("서버 재시작을 예약하지 못했습니다.",NamedTextColor.RED));
            plugin.getLogger().warning("Server reboot could not be scheduled: "+error.getMessage());
            return true;
        }
        pending=true;
        Bukkit.broadcast(Component.text("서버가 곧 재시작됩니다.",NamedTextColor.YELLOW));
        plugin.getLogger().info("Server reboot requested by "+sender.getName());
        return true;
    }
}
