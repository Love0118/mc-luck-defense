package dev.moma.paper;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;

final class RebootCommand extends Command {
    private final MomaPlugin plugin;
    private boolean pending;

    private RebootCommand(MomaPlugin plugin) {
        super("reboot","서버를 정상 종료한 뒤 운영 서비스가 재시작합니다.","/reboot",List.of());
        this.plugin=plugin;
        setPermission("moma.admin");
    }

    static RebootCommand register(MomaPlugin plugin) {
        CommandMap map=Bukkit.getCommandMap();
        if(map.getCommand("reboot")!=null)throw new IllegalStateException("/reboot 명령어가 이미 등록되어 있습니다.");
        RebootCommand command=new RebootCommand(plugin);
        if(!map.register("mcluckdefense",command)) {
            command.unregister(map);
            map.getKnownCommands().entrySet().removeIf(entry->entry.getValue()==command);
            throw new IllegalStateException("/reboot 명령어를 등록할 수 없습니다.");
        }
        Bukkit.getOnlinePlayers().forEach(player->player.updateCommands());
        return command;
    }

    void remove() {
        CommandMap map=Bukkit.getCommandMap();
        map.getKnownCommands().entrySet().removeIf(entry->entry.getValue()==this);
        unregister(map);
        Bukkit.getOnlinePlayers().forEach(player->player.updateCommands());
    }

    @Override public boolean execute(CommandSender sender,String label,String[] args) {
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
