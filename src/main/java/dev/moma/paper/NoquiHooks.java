package dev.moma.paper;

import org.bukkit.entity.Player;

/** Keeps the optional plugin's API out of standalone Moma paths. */
interface NoquiHooks extends AutoCloseable {
    NoquiHooks NONE=new NoquiHooks() {};
    default boolean owns(Player player){return false;}
    default boolean routeJoin(Player player){return false;}
    default void leave(Player player){}
    @Override default void close(){}
}
