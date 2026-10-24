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
        long balance=10_000;boolean failAfterDebit,failAfterCredit;int debits,credits;
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
    @Test void upgradesPersistIndependentlyAndCapsNeverDebitAgain()throws Exception {
        var wallet=new Wallet();
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            store.save(player,MiningStoreTest.session());var economy=new MiningEconomy(store);
            for(int i=0;i<6;i++)economy.buy(player,MiningRules.Upgrade.SPEED,30,wallet);
            for(int i=0;i<3;i++)economy.buy(player,MiningRules.Upgrade.FORTUNE,60,wallet);
            assertEquals(6,store.profile(player).speed());assertEquals(3,store.profile(player).fortune());assertEquals(9,wallet.debits);
            assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.SPEED,30,wallet));
            assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.FORTUNE,60,wallet));assertEquals(9,wallet.debits);
        }
    }
    @Test void insufficientBalanceClearsJournalWithoutChangingUpgrade()throws Exception {
        var wallet=new Wallet();wallet.balance=1;
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            store.save(player,MiningStoreTest.session());var economy=new MiningEconomy(store);
            assertThrows(IllegalArgumentException.class,()->economy.buy(player,MiningRules.Upgrade.SPEED,30,wallet));
            assertEquals(0,store.profile(player).speed());assertNull(store.profile(player).pending());assertEquals(1,wallet.balance);
        }
    }
    @Test void ambiguousDebitReplaysExactlyOnceAcrossRestart()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterDebit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session());
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store).buy(player,MiningRules.Upgrade.SPEED,30,wallet));
            assertNotNull(store.profile(player).pending());assertEquals(0,store.profile(player).speed());
        }
        try(var store=new MiningStore(file)) {
            new MiningEconomy(store).settle(player,wallet);assertEquals(1,store.profile(player).speed());assertNull(store.profile(player).pending());
            assertEquals(1,wallet.debits);assertEquals(9970,wallet.balance);
        }
    }
    @Test void saleUsesOnlyDurableCargoAndAmbiguousCreditCannotDoublePay()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterCredit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session().credit(MiningRules.Ore.DIAMOND,3));
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store).sell(player,Map.of(),wallet));
            assertTrue(store.profile(player).cargo().isEmpty());assertEquals(MiningStore.Operation.SALE,store.profile(player).pending().operation());
        }
        try(var store=new MiningStore(file)) {
            var economy=new MiningEconomy(store);economy.settle(player,wallet);assertEquals(0,economy.sell(player,Map.of(),wallet));
            assertEquals(1,wallet.credits);assertEquals(10012,wallet.balance);assertNull(store.profile(player).pending());
        }
    }
    @Test void rejectedUpgradeCommitProducesDurableIdempotentRefund()throws Exception {
        Path file=directory.resolve("mining.db");var wallet=new Wallet();wallet.failAfterCredit=true;
        try(var store=new MiningStore(file)) {
            store.save(player,MiningStoreTest.session());
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var sql=connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_upgrade BEFORE UPDATE ON mining_profiles WHEN substr(NEW.data,21,4)=X'00000001' BEGIN SELECT RAISE(ABORT,'simulated upgrade save failure'); END");
            }
            assertThrows(IllegalStateException.class,()->new MiningEconomy(store).buy(player,MiningRules.Upgrade.SPEED,30,wallet));
            assertEquals(0,store.profile(player).speed());assertEquals(MiningStore.Operation.REFUND,store.profile(player).pending().operation());
        }
        try(var store=new MiningStore(file)) {
            new MiningEconomy(store).settle(player,wallet);assertNull(store.profile(player).pending());assertEquals(0,store.profile(player).speed());
            assertEquals(1,wallet.debits);assertEquals(1,wallet.credits);assertEquals(10000,wallet.balance);
        }
    }
    @Test void failureBeforeJournalCommitNeverCallsWallet()throws Exception {
        var wallet=new Wallet();var store=new MiningStore(directory.resolve("mining.db"));store.save(player,MiningStoreTest.session());store.close();
        assertThrows(IOException.class,()->new MiningEconomy(store).buy(player,MiningRules.Upgrade.SPEED,30,wallet));assertEquals(0,wallet.debits);
        assertThrows(IOException.class,()->new MiningEconomy(store).sell(player,Map.of(),wallet));assertEquals(0,wallet.credits);
    }
}
