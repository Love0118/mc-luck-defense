package dev.moma.paper;

import org.bukkit.entity.Player;
import java.util.UUID;

/** Keeps the optional plugin's API out of standalone Moma paths. */
interface NoquiHooks extends AutoCloseable {
    NoquiHooks NONE=new NoquiHooks() {};
    default boolean started(){return false;}
    default boolean owns(Player player){return false;}
    default boolean participant(Player player){return false;}
    default void openControls(Player player){}
    default void prepareDefense(Player player){}
    default void defenseEntered(Player player){}
    default void defenseFailed(Player player){}
    default boolean rewardStarlight(UUID player,String transactionId,long amount){return false;}
    default boolean routeJoin(Player player){return false;}
    default void leave(Player player){}
    @Override default void close(){}
}
