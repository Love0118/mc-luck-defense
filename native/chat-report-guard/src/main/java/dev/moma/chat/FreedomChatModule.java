package dev.moma.chat;

import io.netty.channel.Channel;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import ru.bk.oharass.freedomchat.FreedomChat;

/** GPL-3.0 module: upstream packet rewriting, also attached to connections already online. */
public final class FreedomChatModule extends FreedomChat {
    private final Set<Channel> channels=ConcurrentHashMap.newKeySet();
    private static final Key KEY=Key.key("freedomchat","listener");

    @Override public void onEnable(){
        super.onEnable();
        if(!isEnabled())return;
        var upstream=ChannelInitializeListenerHolder.removeListener(KEY);
        if(upstream==null)throw new IllegalStateException("FreedomChat did not install its protocol hook");
        io.papermc.paper.network.ChannelInitializeListener attach=channel->{
            if(channels.add(channel))channel.closeFuture().addListener(closed->channels.remove(channel));
            if(channel.pipeline().get("freedom_handler")==null)upstream.afterInitChannel(channel);
        };
        ChannelInitializeListenerHolder.addListener(KEY,attach);
        for(var player:Bukkit.getOnlinePlayers()){
            Channel channel=((CraftPlayer)player).getHandle().connection.connection.channel;
            channel.eventLoop().execute(()->attach.afterInitChannel(channel));
        }
        getLogger().info("Outbound chat-report protection active, including native private messages");
    }

    @Override public void onDisable(){
        super.onDisable();
        for(Channel channel:channels)channel.eventLoop().execute(()->{
            if(channel.pipeline().get("freedom_handler")!=null)channel.pipeline().remove("freedom_handler");
        });
        channels.clear();
    }
}
