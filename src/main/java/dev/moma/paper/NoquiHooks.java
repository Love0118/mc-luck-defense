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
    default void openWardrobe(Player player){}
    default void openSharedShop(Player player){}
    default void openMiningShop(Player player,Runnable sales,Runnable upgrade){}
    default void prepareItems(Player player){}
    default void restoreSharedInventory(Player player){}
    default void saveSharedInventory(Player player){}
    default long starlight(UUID player){throw new IllegalStateException("별빛 지갑이 준비되지 않았습니다.");}
    default boolean spendStarlight(UUID player,String transaction,long amount){throw new IllegalStateException("별빛 지갑이 준비되지 않았습니다.");}
    default void openProxy(Player player){}
    default long ticketCount(UUID player){return 0;}
    default void prepareDefense(Player player){}
    default void defenseEntered(Player player){}
    default void defenseFailed(Player player){}
    default boolean rewardStarlight(UUID player,String transactionId,long amount){return false;}
    default boolean routeJoin(Player player){return false;}
    default void leave(Player player){}
    @Override default void close(){}
}
