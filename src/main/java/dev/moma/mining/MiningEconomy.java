package dev.moma.mining;

import java.io.IOException;
import java.util.*;

/** Host wallet transaction IDs must be durable and idempotent, including replayed debits. */
final class MiningEconomy {
    interface Wallet { boolean spend(String transaction,long amount); void reward(String transaction,long amount); }
    private final MiningStore store;
    private final java.util.random.RandomGenerator random;
    MiningEconomy(MiningStore store) { this(store,java.util.concurrent.ThreadLocalRandom.current()); }
    MiningEconomy(MiningStore store,java.util.random.RandomGenerator random) { this.store=store;this.random=random; }
    MiningRules.Attempt buy(UUID player,MiningRules.Upgrade upgrade,Wallet wallet)throws IOException {
        settle(player,wallet);
        var p=store.profile(player);
        int level=upgrade==MiningRules.Upgrade.SPEED?p.speed():p.fortune();
        long amount=MiningRules.offer(upgrade,level).cost();var result=MiningRules.roll(upgrade,level,random);
        var operation=upgrade==MiningRules.Upgrade.SPEED?MiningStore.Operation.SPEED:MiningStore.Operation.FORTUNE;
        store.save(player,p.pending(new MiningStore.Pending("mining:"+UUID.randomUUID(),operation,amount,result.outcome(),result.gain())));
        settle(player,wallet);
        return result;
    }
    long sell(UUID player,Map<MiningRules.Ore,Long> prices,Wallet wallet)throws IOException {
        settle(player,wallet);
        var p=store.profile(player); long amount=MiningRules.sale(p.cargo(),prices);
        if(amount==0)return 0;
        // Cargo disappears in the same commit as the payout journal; retries cannot sell it twice.
        store.save(player,p.emptyCargo(new MiningStore.Pending("mining:"+UUID.randomUUID(),MiningStore.Operation.SALE,amount)));
        settle(player,wallet);return amount;
    }
    void settle(UUID player,Wallet wallet)throws IOException {
        var p=store.profile(player);var pending=p.pending();if(pending==null)return;
        switch(pending.operation()) {
            case SALE,REFUND -> { wallet.reward(pending.id()+(pending.operation()==MiningStore.Operation.REFUND?":refund":""),pending.amount());store.save(player,p.pending(null)); }
            case SPEED,FORTUNE -> {
                if(!wallet.spend(pending.id(),pending.amount())) {
                    store.save(player,p.pending(null));throw new IllegalArgumentException("별빛이 부족합니다.");
                }
                try { store.save(player,p.upgrade(pending)); }
                catch(IOException commitFailure) {
                    // Never issue an unjournaled refund. If storage is still down, retain PURCHASE for replay.
                    var refund=p.pending(new MiningStore.Pending(pending.id(),MiningStore.Operation.REFUND,pending.amount()));
                    try { store.save(player,refund); } catch(IOException failure) { commitFailure.addSuppressed(failure);throw commitFailure; }
                    wallet.reward(pending.id()+":refund",pending.amount());store.save(player,refund.pending(null));
                    throw commitFailure;
                }
            }
        }
    }
}
