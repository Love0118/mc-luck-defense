package dev.moma.mining;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MiningEconomyTest {
    @TempDir Path directory;
    private final UUID player=UUID.randomUUID();
    private static final class Wallet implements MiningEconomy.Wallet {
        long balance=100_000;boolean failAfterDebit,failAfterCredit;int debits,credits;
        final Set<String> receipts=new HashSet<>();
        public boolean spend(String id,long amount){
            if(receipts.contains(id))return true;if(balance<amount)return false;
            balance-=amount;debits++;receipts.add(id);
            if(failAfterDebit){failAfterDebit=false;throw new IllegalStateException("Disconnected after wallet commit");}return true;
        }
        public void reward(String id,long amount){
            if(receipts.add(id)){balance+=amount;credits++;}
            if(failAfterCredit){failAfterCredit=false;throw new IllegalStateException("Disconnected after wallet commit");}
        }
    }
    private static Random success(){return new Random(){@Override public int nextInt(int bound){return 0;}@Override public int nextInt(int origin,int bound){return origin;}};}
    @Test void upgradesPersistIndependentlyAndCapsNeverDebitAgain()throws Exception {
        var wallet=new Wallet();try(var store=new MiningStore(directory.resolve("mining.db"))){
            var p=MiningStoreTest.session();store.save(player,new MiningStore.Profile(p.tool(),199,2,p.cargo(),p.recovery(),null));var economy=new MiningEconomy(store,success());
            assertEquals(1,economy.buy(player,MiningRules.Upgrade.SPEED,wallet).gain());economy.buy(player,MiningRules.Upgrade.FORTUNE,wallet);
            assertEquals(200,store.profile(player).speed());assertEquals(3,store.profile(player).fortune());assertEquals(2,wallet.debits);
            assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.SPEED,wallet));assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.FORTUNE,wallet));assertEquals(2,wallet.debits);
        }
    }
    @Test void insufficientBalanceClearsJournalWithoutChangingUpgrade()throws Exception {
        var wallet=new Wallet();wallet.balance=1;
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            store.save(player,MiningStoreTest.session());var economy=new MiningEconomy(store,success());
            assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.SPEED,wallet));
            assertEquals(50,store.profile(player).speed());assertNull(store.profile(player).pending());assertEquals(1,wallet.balance);
        }
    }
    @Test void ambiguousDebitReplaysExactlyOnceAcrossRestart()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterDebit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session());
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store,success()).buy(player,MiningRules.Upgrade.SPEED,wallet));
            assertNotNull(store.profile(player).pending());assertEquals(50,store.profile(player).speed());
        }
        try(var store=new MiningStore(file)) {
            new MiningEconomy(store,success()).settle(player,wallet);assertEquals(51,store.profile(player).speed());assertNull(store.profile(player).pending());
            assertEquals(1,wallet.debits);assertEquals(99850,wallet.balance);
        }
    }
    @Test void saleUsesOnlyDurableCargoAndAmbiguousCreditCannotDoublePay()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterCredit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session().credit(MiningRules.Ore.DIAMOND,3));
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store,success()).sell(player,Map.of(),wallet));
            assertTrue(store.profile(player).cargo().isEmpty());assertEquals(MiningStore.Operation.SALE,store.profile(player).pending().operation());
        }
        try(var store=new MiningStore(file)) {
            var economy=new MiningEconomy(store,success());economy.settle(player,wallet);assertEquals(0,economy.sell(player,Map.of(),wallet));
            assertEquals(1,wallet.credits);assertEquals(100012,wallet.balance);assertNull(store.profile(player).pending());
        }
    }
    @Test void rejectedUpgradeCommitProducesDurableIdempotentRefund()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterCredit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session());
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var sql=connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_upgrade BEFORE UPDATE ON mining_profiles WHEN substr(NEW.data,21,4)=X'00000033' BEGIN SELECT RAISE(ABORT,'simulated upgrade save failure'); END");
            }
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store,success()).buy(player,MiningRules.Upgrade.SPEED,wallet));
            assertEquals(50,store.profile(player).speed());assertEquals(MiningStore.Operation.REFUND,store.profile(player).pending().operation());
        }
        try(var store=new MiningStore(file)) {
            new MiningEconomy(store,success()).settle(player,wallet);assertNull(store.profile(player).pending());assertEquals(50,store.profile(player).speed());
            assertEquals(1,wallet.debits);assertEquals(1,wallet.credits);assertEquals(100000,wallet.balance);
        }
    }
    @Test void failureBeforeJournalCommitNeverCallsWallet()throws Exception {
        var wallet=new Wallet();var store=new MiningStore(directory.resolve("mining.db"));store.save(player,MiningStoreTest.session());store.close();
        assertThrows(IOException.class,()->new MiningEconomy(store,success()).buy(player,MiningRules.Upgrade.SPEED,wallet));assertEquals(0,wallet.debits);
        assertThrows(IOException.class,()->new MiningEconomy(store,success()).sell(player,Map.of(),wallet));assertEquals(0,wallet.credits);
    }
    @Test void failureKeepsStatsAndDestructionResetsBothStatsWithoutFreeAttempts()throws Exception{
        var wallet=new Wallet();try(var store=new MiningStore(directory.resolve("mine.db"))){
            var p=MiningStoreTest.session();store.save(player,p);
            var fail=new MiningEconomy(store,new Random(){@Override public int nextInt(int bound){return 99;}});
            assertEquals(MiningRules.Outcome.FAILURE,fail.buy(player,MiningRules.Upgrade.SPEED,wallet).outcome());
            assertEquals(50,store.profile(player).speed());assertEquals(99850,wallet.balance);
            p=store.profile(player);store.save(player,new MiningStore.Profile(p.tool(),150,2,p.cargo(),p.recovery(),null));
            var destroy=new MiningEconomy(store,new Random(){@Override public int nextInt(int bound){return 55;}});
            assertEquals(MiningRules.Outcome.DESTROYED,destroy.buy(player,MiningRules.Upgrade.SPEED,wallet).outcome());
            var after=store.profile(player);assertEquals(50,after.speed());assertEquals(0,after.fortune());assertNotEquals(p.tool(),after.tool());assertEquals(98550,wallet.balance);
        }
    }
}
