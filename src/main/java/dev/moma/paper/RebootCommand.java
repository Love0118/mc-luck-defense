package dev.moma.paper;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

final class RebootCommand extends Command {
    private final MomaPlugin plugin;
    private boolean pending;
    private BukkitTask idleTask;

    private RebootCommand(MomaPlugin plugin) {
        super("reboot","서버를 정상 종료한 뒤 운영 서비스가 재시작합니다.","/reboot [est|cancel]",List.of());
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
        cancelIdle();
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
        if(args.length==1 && args[0].equalsIgnoreCase("est"))return waitUntilEmpty(sender);
        if(args.length==1 && args[0].equalsIgnoreCase("cancel")) {
            if(idleTask==null) sender.sendMessage(Component.text("대기 중인 자동 재시작이 없습니다.",NamedTextColor.YELLOW));
            else {
                cancelIdle();
                sender.sendMessage(Component.text("자동 재시작 대기를 취소했습니다.",NamedTextColor.GREEN));
            }
            return true;
        }
        if(args.length!=0) {
            sender.sendMessage(Component.text("/reboot [est|cancel]",NamedTextColor.YELLOW));
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
        cancelIdle();
        Bukkit.broadcast(Component.text("서버가 곧 재시작됩니다.",NamedTextColor.YELLOW));
        plugin.getLogger().info("Server reboot requested by "+sender.getName());
        return true;
    }

    private boolean waitUntilEmpty(CommandSender sender) {
        if(pending) {
            sender.sendMessage(Component.text("서버 재시작이 이미 예약되었습니다.",NamedTextColor.YELLOW));
            return true;
        }
        if(idleTask!=null) {
            sender.sendMessage(Component.text("접속자가 없어지면 자동 재시작하도록 이미 대기 중입니다.",NamedTextColor.YELLOW));
            return true;
        }
        try {
            idleTask=Bukkit.getScheduler().runTaskTimer(plugin,this::rebootWhenEmpty,20L,20L);
        } catch(IllegalStateException error) {
            sender.sendMessage(Component.text("자동 재시작 대기를 시작하지 못했습니다.",NamedTextColor.RED));
            plugin.getLogger().warning("Idle reboot could not be scheduled: "+error.getMessage());
            return true;
        }
        sender.sendMessage(Component.text("접속자가 모두 나가면 서버를 재시작합니다. 취소: /reboot cancel",NamedTextColor.GREEN));
        plugin.getLogger().info("Idle reboot requested by "+sender.getName());
        return true;
    }

    private void rebootWhenEmpty() {
        if(pending || !Bukkit.getOnlinePlayers().isEmpty())return;
        pending=true;
        cancelIdle();
        plugin.getLogger().info("No players online; restarting server now");
        Bukkit.shutdown();
    }

    private void cancelIdle() {
        if(idleTask==null)return;
        idleTask.cancel();
        idleTask=null;
    }
}
